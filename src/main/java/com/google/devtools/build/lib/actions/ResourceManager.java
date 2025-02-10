// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.actions;

import static com.google.devtools.build.lib.profiler.AutoProfiler.profiled;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.worker.Worker;
import com.google.devtools.build.lib.worker.WorkerKey;
import com.google.devtools.build.lib.worker.WorkerPool;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import java.io.IOException;
import java.time.Duration;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Used to keep track of resources consumed by the Blaze action execution threads and throttle them
 * when necessary.
 *
 * <p>Threads which are known to consume a significant amount of resources should call {@link
 * #acquireResources} method. This method will check whether requested resources are available and
 * will either mark them as used and allow the thread to proceed or will block the thread until
 * requested resources will become available. When the thread completes its task, it must release
 * allocated resources by calling {@link #releaseResources} method.
 *
 * <p>Available resources can be calculated using one of three ways:
 *
 * <ol>
 *   <li>They can be preset using {@link #setAvailableResources(ResourceSet)} method. This is used
 *       mainly by the unit tests (however it is possible to provide a future option that would
 *       artificially limit amount of CPU/RAM consumed by the Blaze).
 *   <li>They can be preset based on the /proc/cpuinfo and /proc/meminfo information. Blaze will
 *       calculate amount of available CPU cores (adjusting for hyperthreading logical cores) and
 *       amount of the total available memory and will limit itself to the number of effective cores
 *       and 2/3 of the available memory. For details, please look at the {@link
 *       LocalHostCapacity#getLocalHostCapacity} method.
 * </ol>
 *
 * <p>The resource manager also allows a slight overallocation of the resources to account for the
 * fact that requested resources are usually estimated using a pessimistic approximation. It also
 * guarantees that at least one thread will always be able to acquire any amount of requested
 * resources (even if it is greater than amount of available resources). Therefore, assuming that
 * threads correctly release acquired resources, Blaze will never be fully blocked.
 */
@ThreadSafe
public class ResourceManager implements ResourceEstimator {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * A handle returned by {@link #acquireResources(ActionExecutionMetadata, ResourceSet,
   * ResourcePriority)} that must be closed in order to free the resources again.
   */
  public static class ResourceHandle implements AutoCloseable {
    private final ResourceManager manager;
    private Worker worker;
    private final ResourceRequest request;
    private final long resourceAcquiredTime;

    private ResourceHandle(ResourceManager manager, ResourceRequest request, Worker worker) {
      this.manager = manager;
      this.resourceAcquiredTime = BlazeClock.instance().nanoTime();
      this.worker = worker;
      this.request = request;
    }

    @Nullable
    public Worker getWorker() {
      return worker;
    }

    @VisibleForTesting
    ResourceRequest getRequest() {
      return request;
    }

    /** Closing the ResourceHandle releases the resources associated with it. */
    @Override
    public void close() throws IOException, InterruptedException {
      manager.releaseResources(request, worker);
      Profiler.instance()
          .completeTask(
              resourceAcquiredTime, ProfilerTask.LOCAL_ACTION_COUNTS, "Resources acquired");
    }

    public void invalidateAndClose(@Nullable Exception e) throws IOException, InterruptedException {
      // If there is an exception, we need to set the kill cause before invalidating the object.
      // This ensures that the worker implementation updates their worker metrics accordingly
      // if/when it destroys itself.
      if (e != null) {
        if (e instanceof InterruptedException) {
          worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION);
        } else if (e instanceof IOException) {
          worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_IO_EXCEPTION);
        } else if (e instanceof UserExecException userExecException) {
          if (userExecException.getFailureDetail().hasWorker()) {
            worker
                .getStatus()
                .maybeUpdateStatus(
                    Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION,
                    userExecException.getFailureDetail().getWorker().getCode());
          }
        } else {
          worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION);
        }
      } else {
        worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_UNKNOWN);
      }

      manager.workerPool.invalidateWorker(worker);
      worker = null;
      this.close();
    }
  }

  private final ThreadLocal<Boolean> threadLocked =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  /**
   * Defines the possible priorities of resources. The earlier elements in this enum will get first
   * chance at grabbing resources.
   */
  public enum ResourcePriority {
    LOCAL(), // Local execution not under dynamic execution
    DYNAMIC_WORKER(),
    DYNAMIC_STANDALONE();
  }

  /** Singleton reference defined in a separate class to ensure thread-safe lazy initialization. */
  private static class Singleton {
    static final ResourceManager instance = new ResourceManager();
  }

  /** Returns singleton instance of the resource manager. */
  public static ResourceManager instance() {
    return Singleton.instance;
  }

  /** Returns prediction of RAM in Mb used by registered actions. */
  @Override
  public double getUsedMemoryInMb() {
    return usedResources.getOrDefault(ResourceSet.MEMORY, 0d);
  }

  /** Returns prediction of CPUs used by registered actions. */
  @Override
  public double getUsedCPU() {
    return usedResources.getOrDefault(ResourceSet.CPU, 0d);
  }

  // Allocated resources are allowed to go "negative", but at least
  // MIN_NECESSARY_RATIO portion of each resource should be available.
  // Please note that this value is purely empirical - we assume that generally
  // requested resources are somewhat pessimistic and thread would end up
  // using less than requested amount.
  private static final Double DEFAULT_MIN_NECESSARY_RATIO = 1.0;
  private static final ImmutableMap<String, Double> MIN_NECESSARY_RATIO =
      ImmutableMap.of(ResourceSet.CPU, 0.6);
  private static final int MAX_ACTIONS_PER_CPU = 3;

  // Pair of requested resources and latch represented it for waiting.
  record WaitingRequest(ResourceRequest getResourceRequest, ResourceLatch getResourceLatch) {}
  ;

  // Lists of blocked threads. Associated CountDownLatch object will always
  // be initialized to 1 during creation in the acquire() method.
  // We use LinkedList because we will need to remove elements from the middle frequently in the
  // middle of iterating through the list.
  @SuppressWarnings("JdkObsolete")
  private final Deque<WaitingRequest> localRequests = new LinkedList<>();

  @SuppressWarnings("JdkObsolete")
  private final Deque<WaitingRequest> dynamicWorkerRequests = new LinkedList<>();

  @SuppressWarnings("JdkObsolete")
  private final Deque<WaitingRequest> dynamicStandaloneRequests = new LinkedList<>();

  private WorkerPool workerPool;

  // The total amount of available for Bazel resources on the local host. Must be set by
  // an explicit call to setAvailableResources(), often using
  // LocalHostCapacity.getLocalHostCapacity() as an argument.
  @VisibleForTesting public ResourceSet availableResources = null;

  // Used amount of resources. Corresponds to the resource
  // definition in the ResourceSet class.
  private Map<String, Double> usedResources = new HashMap<>();

  // Used local test count. Corresponds to the local test count definition in the ResourceSet class.
  private int usedLocalTestCount;

  // The following flags are responsible for experimental action scheduling based on load of the
  // machine.
  //
  // With this functionality the whole timeline is splitted on the window of the same duration.
  // In this case the CPU usage by blaze is defined by the formula:
  // CPU usage = System CPU load + Window estimation.
  // System CPU load defined by information about system running blaze process.
  // Window estimation is an sum of ResourceSets defined for all action started to run during this
  // window. This term added to compensate the pressure by actions which are started to run during
  // the window but not represented on CPU load yet.

  // Experimental scheduling have showed the large benefit on a large local builds on a powerful
  // machines with the large number of cores.
  // The known issue with this flag that it cannot distinguish the load of Bazel and load of
  // different process on the machine, so it tries to load machine no more than defined in flag
  // local_resources, so for better utilization it's recommended to set
  // --local_resources=cpu=HOST_CPUS.

  // Enables experimental action scheduling using CPU load of a machine.
  private boolean cpuLoadScheduling;
  // The size of window for running actions.
  private Duration windowSize = Duration.ofSeconds(5);
  // Estimation of CPU usage by actions started during the window.
  private double windowEstimationCpu;
  // Set of request ids which resource acquiring started during the window.
  private final Set<Integer> windowRequestIds = new HashSet<>();
  // Executor for periodic window update.
  ScheduledExecutorService windowUpdateExecutor = Executors.newScheduledThreadPool(1);
  // Future for periodic window update.
  ScheduledFuture<?> windowUpdateFuture = null;
  // Total number of actions running locally.
  private int runningActions = 0;
  // Collects the information about the load of a machine.
  private MachineLoadProvider machineLoadProvider;

  public void initializeCpuLoadFunctionality(
      MachineLoadProvider machineLoadProvider, boolean cpuLoadScheduling, Duration windowSize) {
    this.machineLoadProvider = machineLoadProvider;
    this.cpuLoadScheduling = cpuLoadScheduling;
    this.windowSize = windowSize;
  }

  class WindowUpdateRunner extends Thread {
    public WindowUpdateRunner(String name) {
      super(name);
    }

    @Override
    public void run() {
      try {
        windowUpdate();
      } catch (IOException | InterruptedException e) {
        logger.atWarning().withCause(e).log(
            "Exception while updating window of locally scheduled action: %s", e);
      }
    }
  }

  synchronized void windowUpdate() throws IOException, InterruptedException {
    windowRequestIds.clear();
    windowEstimationCpu = 0.0;
    processAllWaitingRequests();
  }

  @VisibleForTesting
  public static ResourceManager instanceForTestingOnly() {
    return new ResourceManager();
  }

  /**
   * Resets resource manager state and releases all thread locks.
   *
   * <p>Note - it does not reset available resources. Use separate call to setAvailableResources().
   */
  public synchronized void resetResourceUsage() {
    usedResources = new HashMap<>();
    usedLocalTestCount = 0;
    for (WaitingRequest request : localRequests) {
      request.getResourceLatch().getLatch().countDown();
    }
    for (WaitingRequest request : dynamicWorkerRequests) {
      request.getResourceLatch().getLatch().countDown();
    }
    for (WaitingRequest request : dynamicStandaloneRequests) {
      request.getResourceLatch().getLatch().countDown();
    }
    localRequests.clear();
    dynamicWorkerRequests.clear();
    dynamicStandaloneRequests.clear();

    windowRequestIds.clear();
    windowEstimationCpu = 0.0;
    runningActions = 0;
  }

  /**
   * Sets available resources using given resource set.
   *
   * <p>Must be called at least once before using resource manager.
   */
  public synchronized void setAvailableResources(ResourceSet resources) {
    Preconditions.checkNotNull(resources);
    resetResourceUsage();
    availableResources = resources;
  }

  public synchronized void scheduleCpuLoadWindowUpdate() {
    if (windowUpdateFuture != null) {
      windowUpdateFuture.cancel(true);
    }

    if (cpuLoadScheduling) {
      windowUpdateFuture =
          windowUpdateExecutor.scheduleAtFixedRate(
              new WindowUpdateRunner("window-update"), 0, windowSize.toMillis(), MILLISECONDS);
    }
  }

  /** Sets worker pool for taking the workers. Must be called before requesting the workers. */
  public void setWorkerPool(WorkerPool workerPool) {
    this.workerPool = workerPool;
  }

  /** Generates the ids for requests */
  private static final AtomicInteger requestIdGenerator = new AtomicInteger(0);

  /** Request with the information of resource acquiring. */
  record ResourceRequest(
      ActionExecutionMetadata getOwner,
      ResourceSet getResourceSet,
      ResourcePriority getPriority,
      int getId) {}
  ;

  /**
   * Acquires requested resource set. Will block if resource is not available. NB! This method must
   * be thread-safe!
   */
  public ResourceHandle acquireResources(
      ActionExecutionMetadata owner, ResourceSet resources, ResourcePriority priority)
      throws InterruptedException, IOException, ExecException {
    Preconditions.checkNotNull(
        resources, "acquireResources called with resources == NULL during %s", owner);
    Preconditions.checkState(
        !threadHasResources(), "acquireResources with existing resource lock during %s", owner);

    ResourceLatch resourceLatch = null;

    // Validate requested resources exist before creating a request.
    assertResourcesTracked(resources);
    ResourceRequest request =
        new ResourceRequest(owner, resources, priority, requestIdGenerator.getAndIncrement());

    AutoProfiler p =
        profiled("Acquiring resources for: " + owner.describe(), ProfilerTask.ACTION_LOCK);
    try {
      resourceLatch = acquire(request);
      if (resourceLatch.getLatch() != null) {
        resourceLatch.getLatch().await();
      }
    } catch (InterruptedException e) {
      // Synchronize on this to avoid any racing with #processWaitingRequests
      synchronized (this) {
        if (resourceLatch != null) {
          if (resourceLatch.getLatch() == null || resourceLatch.getLatch().getCount() == 0) {
            // Resources already acquired by other side. Release them, but not inside this
            // synchronized block to avoid deadlock.
            release(request, resourceLatch.getWorker());
          } else {
            // Inform other side that resources shouldn't be acquired.
            resourceLatch.getLatch().countDown();
          }
        }
      }
      throw e;
    }

    threadLocked.set(true);

    CountDownLatch latch;
    Worker worker;
    synchronized (this) {
      latch = resourceLatch.getLatch();
      worker = resourceLatch.getWorker();
    }

    // Profile acquisition only if it waited for resource to become available.
    if (latch != null) {
      p.complete();
    }

    return new ResourceHandle(this, request, worker);
  }

  @Nullable
  private synchronized Worker incrementResources(ResourceRequest request)
      throws IOException, InterruptedException {
    ResourceSet resources = request.getResourceSet();

    resources
        .getResources()
        .forEach(
            (key, value) -> {
              if (usedResources.containsKey(key)) {
                value += usedResources.get(key);
              }
              usedResources.put(key, value);
            });

    windowRequestIds.add(request.getId());
    windowEstimationCpu += resources.getResources().getOrDefault(ResourceSet.CPU, 0.0);
    usedLocalTestCount += resources.getLocalTestCount();
    if (resources.getWorkerKey() != null) {
      return this.workerPool.borrowWorker(resources.getWorkerKey());
    }

    runningActions++;
    return null;
  }

  /** Return true if any resources have been claimed through this manager. */
  public synchronized boolean inUse() {
    return !usedResources.isEmpty()
        || usedLocalTestCount != 0
        || !localRequests.isEmpty()
        || !dynamicWorkerRequests.isEmpty()
        || !dynamicStandaloneRequests.isEmpty();
  }

  /** Return true iff this thread has a lock on non-zero resources. */
  public boolean threadHasResources() {
    return threadLocked.get();
  }

  /**
   * Releases previously requested resource.
   *
   * <p>NB! This method must be thread-safe!
   *
   * @param request initial request of resource acquiring
   * @param worker the worker, which used during execution
   * @throws java.io.IOException if could not return worker to the workerPool
   */
  void releaseResources(ResourceRequest request, @Nullable Worker worker)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(
        request.getResourceSet(),
        "releaseResources called with resources == NULL during %s",
        request.getOwner());

    Preconditions.checkState(
        threadHasResources(),
        "releaseResources without resource lock during %s",
        request.getOwner());

    try {
      release(request, worker);
    } finally {
      threadLocked.set(false);
    }
  }

  public void releaseResourceOwnership() {
    threadLocked.set(false);
  }

  public void acquireResourceOwnership() {
    threadLocked.set(true);
  }

  /**
   * Returns the pair of worker and latch. Worker should be null if there is no workerKey in
   * resources. The latch isn't null if we could not acquire the resources right now and need to
   * wait.
   */
  private synchronized ResourceLatch acquire(ResourceRequest request)
      throws IOException, InterruptedException {
    if (areResourcesAvailable(request.getResourceSet())) {
      Worker worker = incrementResources(request);
      return new ResourceLatch(/* latch= */ null, worker);
    }
    WaitingRequest waitingRequest =
        new WaitingRequest(request, new ResourceLatch(new CountDownLatch(1), /* worker= */ null));
    switch (request.getPriority()) {
      case LOCAL -> localRequests.addLast(waitingRequest);
      case DYNAMIC_WORKER ->
          // Dynamic requests should be LIFO, because we are more likely to win the race on newer
          // actions.
          dynamicWorkerRequests.addFirst(waitingRequest);
      case DYNAMIC_STANDALONE ->
          // Dynamic requests should be LIFO, because we are more likely to win the race on newer
          // actions.
          dynamicStandaloneRequests.addFirst(waitingRequest);
    }
    return waitingRequest.getResourceLatch();
  }

  /**
   * Release resources and process the queues of waiting threads. Return true when any new thread
   * processed.
   */
  private synchronized void release(ResourceRequest request, @Nullable Worker worker)
      throws IOException, InterruptedException {
    if (worker != null) {
      this.workerPool.returnWorker(worker.getWorkerKey(), worker);
    }

    ResourceSet resources = request.getResourceSet();
    usedLocalTestCount -= resources.getLocalTestCount();
    // TODO(bazel-team): (2010) rounding error can accumulate and value below can end up being
    // e.g. 1E-15. So if it is small enough, we set it to 0. But maybe there is a better solution.
    double epsilon = 0.0001;
    Set<String> toRemove = new HashSet<>();
    for (Map.Entry<String, Double> resource : resources.getResources().entrySet()) {
      String key = resource.getKey();
      double value = usedResources.getOrDefault(key, 0.0) - resource.getValue();
      usedResources.put(key, value);
      if (value < epsilon) {
        toRemove.add(key);
      }
    }
    usedResources.keySet().removeAll(toRemove);
    for (String key : toRemove) {
      usedResources.remove(key);
    }

    if (windowRequestIds.remove(request.getId())) {
      windowEstimationCpu -= resources.getResources().getOrDefault(ResourceSet.CPU, 0.0);
    }
    runningActions--;

    processAllWaitingRequests();
  }

  private synchronized void processAllWaitingRequests() throws IOException, InterruptedException {
    processWaitingRequests(localRequests);
    processWaitingRequests(dynamicWorkerRequests);
    processWaitingRequests(dynamicStandaloneRequests);
  }

  private synchronized void processWaitingRequests(Deque<WaitingRequest> requests)
      throws IOException, InterruptedException {
    if (requests.isEmpty()) {
      return;
    }

    Iterator<WaitingRequest> iterator = requests.iterator();
    while (iterator.hasNext()) {
      WaitingRequest request = iterator.next();
      if (request.getResourceLatch().getLatch().getCount() != 0) {
        if (areResourcesAvailable(request.getResourceRequest().getResourceSet())) {
          Worker worker = incrementResources(request.getResourceRequest());
          request.getResourceLatch().setWorker(worker);
          request.getResourceLatch().getLatch().countDown();
          iterator.remove();
        }
      } else {
        // Cancelled by other side.
        iterator.remove();
      }
    }
  }

  /** Throws an exception if requested extra resource isn't being tracked */
  private void assertResourcesTracked(ResourceSet resources) throws ExecException {
    for (Map.Entry<String, Double> resource : resources.getResources().entrySet()) {
      String key = resource.getKey();
      if (!availableResources.getResources().containsKey(key)) {
        StringBuilder message = new StringBuilder();
        message.append("Resource ");
        message.append(key);
        message.append(" is not being tracked by the resource manager.");
        message.append(" Available resources are: ");
        message.append(String.join(", ", availableResources.getResources().keySet()));
        throw new UserExecException(
            FailureDetails.FailureDetail.newBuilder()
                .setMessage(message.toString())
                .setLocalExecution(
                    FailureDetails.LocalExecution.newBuilder()
                        .setCode(FailureDetails.LocalExecution.Code.UNTRACKED_RESOURCE)
                        .build())
                .build());
      }
    }
  }

  private static <T extends Number> boolean isAvailable(T available, T used, T requested) {
    // Resources are considered available if any one of the conditions below is true:
    // 1) If resource is not requested at all, it is available.
    // 2) If resource is not used at the moment, it is considered to be
    // available regardless of how much is requested. This is necessary to
    // ensure that at any given time, at least one thread is able to acquire
    // resources even if it requests more than available.
    // 3) If used resource amount is less than total available resource amount.
    return requested.doubleValue() == 0
        || used.doubleValue() == 0
        || used.doubleValue() + requested.doubleValue() <= available.doubleValue();
  }

  // Method will return true if all requested resources are considered to be available.
  @VisibleForTesting
  synchronized boolean areResourcesAvailable(ResourceSet resources) {
    Preconditions.checkNotNull(availableResources);
    // Comparison below is robust, since any calculation errors will be fixed
    // by the release() method.

    WorkerKey workerKey = resources.getWorkerKey();
    if (workerKey != null && !this.workerPool.hasAvailableQuota(workerKey)) {
      return false;
    }

    if (usedResources.isEmpty() && usedLocalTestCount == 0) {
      return true;
    }

    int availableLocalTestCount = availableResources.getLocalTestCount();
    if (!isAvailable(availableLocalTestCount, usedLocalTestCount, resources.getLocalTestCount())) {
      return false;
    }

    for (Map.Entry<String, Double> resource : resources.getResources().entrySet()) {
      String key = resource.getKey();

      if (key.equals(ResourceSet.CPU)) {
        if (!isCpuAvailable(resource)) {
          return false;
        }
        continue;
      }
      // Use only MIN_NECESSARY_RATIO of the resource value to check for
      // allocation. This is necessary to account for the fact that most of the
      // requested resource sets use pessimistic estimations. Note that this
      // ratio is used only during comparison - for tracking we will actually
      // mark whole requested amount as used.
      double requested =
          resource.getValue() * MIN_NECESSARY_RATIO.getOrDefault(key, DEFAULT_MIN_NECESSARY_RATIO);
      double used = usedResources.getOrDefault(key, 0.0);
      double available = availableResources.get(key);
      if (!isAvailable(available, used, requested)) {
        return false;
      }
    }
    return true;
  }

  synchronized boolean isCpuAvailable(Map.Entry<String, Double> resource) {
    String key = resource.getKey();

    double requested =
        resource.getValue() * MIN_NECESSARY_RATIO.getOrDefault(key, DEFAULT_MIN_NECESSARY_RATIO);
    double available = availableResources.get(key);
    double used = usedResources.getOrDefault(key, 0.0);

    if (cpuLoadScheduling) {
      double currentUsage = machineLoadProvider.getCurrentCpuUsage();
      double windowEstimation = windowEstimationCpu;
      // Don't allow to run more than x3 of number cores actions simultaneously.
      if (runningActions >= MAX_ACTIONS_PER_CPU * availableResources.get(ResourceSet.CPU)) {
        return false;
      }
      return isAvailable(available, windowEstimation + currentUsage, requested);
    }

    return isAvailable(available, used, requested);
  }

  @VisibleForTesting
  synchronized int getWaitCount() {
    return localRequests.size() + dynamicStandaloneRequests.size() + dynamicWorkerRequests.size();
  }

  // Latch which indicates the availability of resources. Also via this latch worker could be passed
  // when it's ready.
  private static class ResourceLatch {
    private final CountDownLatch latch;
    private Worker worker;

    public ResourceLatch(CountDownLatch latch, Worker worker) {
      this.latch = latch;
      this.worker = worker;
    }

    public CountDownLatch getLatch() {
      return latch;
    }

    public Worker getWorker() {
      return worker;
    }

    public void setWorker(Worker worker) {
      this.worker = worker;
    }
  }
}
