// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec.FieldHandler;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaModuleFlagsProviderApi;
import com.google.errorprone.annotations.Keep;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** A Starlark declared provider that encapsulates all providers that are needed by Java rules. */
@Immutable
public sealed class JavaInfo extends NativeInfo
    implements JavaInfoApi<Artifact, JavaOutput, JavaPluginData>
    permits JavaInfo.RulesJavaJavaInfo, JavaInfo.WorkspaceJavaInfo {

  public static final String STARLARK_NAME = "JavaInfo";

  // Not serialized
  public static final JavaInfoProvider RULES_JAVA_PROVIDER = new RulesJavaJavaInfoProvider();
  // Not serialized
  public static final JavaInfoProvider WORKSPACE_PROVIDER = new WorkspaceJavaInfoProvider();

  @SerializationConstant public static final JavaInfoProvider PROVIDER = new JavaInfoProvider();

  public static NestedSet<Artifact> transitiveRuntimeJars(TransitiveInfoCollection target)
      throws RuleErrorException {
    return transformStarlarkDepsetApi(target, JavaInfo::getTransitiveRuntimeJars);
  }

  private static NestedSet<Artifact> transformStarlarkDepsetApi(
      TransitiveInfoCollection target, Function<JavaInfo, Depset> api) throws RuleErrorException {
    JavaInfo javaInfo = JavaInfo.getJavaInfo(target);
    if (javaInfo != null) {
      try {
        return api.apply(javaInfo).getSet(Artifact.class);
      } catch (TypeException e) {
        throw new RuleErrorException(e.getMessage());
      }
    }
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  public static Optional<JavaCompilationArgsProvider> getCompilationArgsProvider(
      TransitiveInfoCollection target) throws RuleErrorException {
    return Optional.ofNullable(getJavaInfo(target))
        .map(javaInfo -> javaInfo.providerJavaCompilationArgs);
  }

  public static CcInfo ccInfo(TransitiveInfoCollection target) throws RuleErrorException {
    JavaInfo javaInfo = JavaInfo.getJavaInfo(target);
    if (javaInfo != null && javaInfo.providerJavaCcInfo != null) {
      return javaInfo.providerJavaCcInfo.ccInfo();
    }
    return CcInfo.EMPTY;
  }

  /** Marker interface for encapuslated providers */
  public interface JavaInfoInternalProvider {}

  @Nullable
  static <T> T nullIfNone(Object object, Class<T> type) {
    return object != Starlark.NONE ? type.cast(object) : null;
  }

  static final JavaInfo EMPTY_JAVA_INFO_FOR_TESTING =
      new JavaInfo(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          ImmutableList.of(),
          false,
          ImmutableList.of(),
          Location.BUILTIN);

  private final JavaCompilationArgsProvider providerJavaCompilationArgs;
  private final JavaSourceJarsProvider providerJavaSourceJars;
  private final JavaRuleOutputJarsProvider providerJavaRuleOutputJars;
  private final JavaGenJarsProvider providerJavaGenJars;
  private final JavaCompilationInfoProvider providerJavaCompilationInfo;
  private final JavaCcInfoProvider providerJavaCcInfo;
  private final JavaModuleFlagsProvider providerModuleFlags;
  private final JavaPluginInfo providerJavaPlugin;

  /*
   * Contains the .jar files to be put on the runtime classpath by the configured target.
   * <p>Unlike {@link JavaCompilationArgs#getRuntimeJars()}, it does not contain transitive runtime
   * jars, only those produced by the configured target itself.
   *
   * <p>The reason why this field exists is that neverlink libraries do not contain the compiled jar
   * in {@link JavaCompilationArgs#getRuntimeJars()} and those are sometimes needed, for example,
   * for Proguarding (the compile time classpath is not enough because that contains only ijars)
   */
  private final ImmutableList<Artifact> directRuntimeJars;

  /** Java constraints (e.g. "android") that are present on the target. */
  private final ImmutableList<String> javaConstraints;

  // Whether this library should be used only for compilation and not at runtime.
  private final boolean neverlink;

  @Nullable
  public JavaPluginInfo getJavaPluginInfo() {
    return providerJavaPlugin;
  }

  /** Returns the instance for the provided providerClass, or <tt>null</tt> if not present. */
  // TODO(adonovan): rename these three overloads of getProvider to avoid
  // confusion with the unrelated no-arg Info.getProvider method.
  @SuppressWarnings({"UngroupedOverloads", "unchecked"})
  @Nullable
  public <P extends JavaInfoInternalProvider> P getProvider(Class<P> providerClass) {
    if (providerClass == JavaCompilationArgsProvider.class) {
      return (P) providerJavaCompilationArgs;
    } else if (providerClass == JavaSourceJarsProvider.class) {
      return (P) providerJavaSourceJars;
    } else if (providerClass == JavaRuleOutputJarsProvider.class) {
      return (P) providerJavaRuleOutputJars;
    } else if (providerClass == JavaGenJarsProvider.class) {
      return (P) providerJavaGenJars;
    } else if (providerClass == JavaCompilationInfoProvider.class) {
      return (P) providerJavaCompilationInfo;
    } else if (providerClass == JavaCcInfoProvider.class) {
      return (P) providerJavaCcInfo;
    } else if (providerClass == JavaModuleFlagsProvider.class) {
      return (P) providerModuleFlags;
    }
    throw new IllegalArgumentException("unexpected provider: " + providerClass);
  }

  /** Returns a provider of the specified class, fetched from the JavaInfo of the given target. */
  @Nullable
  @VisibleForTesting
  public static <T extends JavaInfoInternalProvider> T getProvider(
      Class<T> providerClass, TransitiveInfoCollection target) throws RuleErrorException {
    JavaInfo javaInfo = getJavaInfo(target);
    if (javaInfo == null) {
      return null;
    }
    return javaInfo.getProvider(providerClass);
  }

  public static JavaInfo getJavaInfo(TransitiveInfoCollection target) throws RuleErrorException {
    JavaInfo info = target.get(PROVIDER);
    if (info == null) {
      info = target.get(RULES_JAVA_PROVIDER);
    }
    if (info == null) {
      info = target.get(WORKSPACE_PROVIDER);
    }
    return info;
  }

  @VisibleForTesting
  public static JavaInfo wrap(Info info) throws RuleErrorException {
    Provider.Key key = info.getProvider().getKey();
    if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
      return RULES_JAVA_PROVIDER.wrap(info);
    } else if (key.equals(WORKSPACE_PROVIDER.getKey())) {
      return WORKSPACE_PROVIDER.wrap(info);
    } else {
      return JavaInfo.PROVIDER.wrap(info);
    }
  }

  private JavaInfo(
      JavaCcInfoProvider javaCcInfoProvider,
      JavaCompilationArgsProvider javaCompilationArgsProvider,
      JavaCompilationInfoProvider javaCompilationInfoProvider,
      JavaGenJarsProvider javaGenJarsProvider,
      JavaModuleFlagsProvider javaModuleFlagsProvider,
      JavaPluginInfo javaPluginInfo,
      JavaRuleOutputJarsProvider javaRuleOutputJarsProvider,
      JavaSourceJarsProvider javaSourceJarsProvider,
      ImmutableList<Artifact> directRuntimeJars,
      boolean neverlink,
      ImmutableList<String> javaConstraints,
      Location creationLocation) {
    super(creationLocation);
    this.directRuntimeJars = directRuntimeJars;
    this.neverlink = neverlink;
    this.javaConstraints = javaConstraints;
    this.providerJavaCcInfo = javaCcInfoProvider;
    this.providerJavaCompilationArgs = javaCompilationArgsProvider;
    this.providerJavaCompilationInfo = javaCompilationInfoProvider;
    this.providerJavaGenJars = javaGenJarsProvider;
    this.providerModuleFlags = javaModuleFlagsProvider;
    this.providerJavaPlugin = javaPluginInfo;
    this.providerJavaRuleOutputJars = javaRuleOutputJarsProvider;
    this.providerJavaSourceJars = javaSourceJarsProvider;
  }

  private JavaInfo(StructImpl javaInfo) throws EvalException, TypeException, RuleErrorException {
    this(
        JavaCcInfoProvider.fromStarlarkJavaInfo(javaInfo),
        JavaCompilationArgsProvider.fromStarlarkJavaInfo(javaInfo),
        JavaCompilationInfoProvider.fromStarlarkJavaInfo(javaInfo),
        JavaGenJarsProvider.from(javaInfo.getValue("annotation_processing")),
        JavaModuleFlagsProvider.fromStarlarkJavaInfo(javaInfo),
        JavaPluginInfo.fromStarlarkJavaInfo(javaInfo),
        JavaRuleOutputJarsProvider.fromStarlarkJavaInfo(javaInfo),
        JavaSourceJarsProvider.fromStarlarkJavaInfo(javaInfo),
        extractDirectRuntimeJars(javaInfo),
        extractNeverLink(javaInfo),
        extractConstraints(javaInfo),
        javaInfo.getCreationLocation());
  }

  private static ImmutableList<Artifact> extractDirectRuntimeJars(StructImpl javaInfo)
      throws EvalException {
    return Sequence.cast(
            javaInfo.getValue("runtime_output_jars"), Artifact.class, "runtime_output_jars")
        .getImmutableList();
  }

  private static boolean extractNeverLink(StructImpl javaInfo) throws EvalException {
    Boolean neverlink = nullIfNone(javaInfo.getValue("_neverlink"), Boolean.class);
    return neverlink != null && neverlink;
  }

  private static ImmutableList<String> extractConstraints(StructImpl javaInfo)
      throws EvalException {
    Object constraints = javaInfo.getValue("_constraints");
    if (constraints == null || constraints == Starlark.NONE) {
      return ImmutableList.of();
    }
    return Sequence.cast(constraints, String.class, "_constraints").getImmutableList();
  }

  @Override
  public JavaInfoProvider getProvider() {
    return PROVIDER;
  }

  @Override
  public boolean isNeverlink() {
    return neverlink;
  }

  @Override
  public Depset /*<Artifact>*/ getTransitiveRuntimeJars() {
    return Depset.of(
        Artifact.class,
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class, JavaCompilationArgsProvider::runtimeJars));
  }

  @Override
  public Depset /*<Artifact>*/ getTransitiveCompileTimeJars() {
    return Depset.of(
        Artifact.class,
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider::transitiveCompileTimeJars));
  }

  @Override
  public Depset /*<Artifact>*/ getCompileTimeJars() {
    NestedSet<Artifact> compileTimeJars =
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class, JavaCompilationArgsProvider::directCompileTimeJars);
    return Depset.of(Artifact.class, compileTimeJars);
  }

  @Override
  public Depset getFullCompileTimeJars() {
    NestedSet<Artifact> fullCompileTimeJars =
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider::directFullCompileTimeJars);
    return Depset.of(Artifact.class, fullCompileTimeJars);
  }

  @Override
  public Sequence<Artifact> getSourceJars() {
    // TODO(#4221) change return type to NestedSet<Artifact>
    ImmutableList<Artifact> sourceJars =
        providerJavaSourceJars == null ? ImmutableList.of() : providerJavaSourceJars.sourceJars();
    return StarlarkList.immutableCopyOf(sourceJars);
  }

  @Override
  @Deprecated
  public JavaRuleOutputJarsProvider getOutputJars() {
    return providerJavaRuleOutputJars;
  }

  @Override
  public ImmutableList<JavaOutput> getJavaOutputs() {
    return providerJavaRuleOutputJars == null
        ? ImmutableList.of()
        : providerJavaRuleOutputJars.javaOutputs();
  }

  @Override
  public JavaGenJarsProvider getGenJarsProvider() {
    return providerJavaGenJars;
  }

  @Override
  public JavaCompilationInfoProvider getCompilationInfoProvider() {
    return providerJavaCompilationInfo;
  }

  @Override
  public Sequence<Artifact> getRuntimeOutputJars() {
    return StarlarkList.immutableCopyOf(getDirectRuntimeJars());
  }

  public ImmutableList<Artifact> getDirectRuntimeJars() {
    return directRuntimeJars;
  }

  @Override
  public Depset /*<Artifact>*/ getTransitiveSourceJars() {
    return Depset.of(
        Artifact.class,
        getProviderAsNestedSet(
            JavaSourceJarsProvider.class, JavaSourceJarsProvider::transitiveSourceJars));
  }

  /** Returns the transitive set of CC native libraries required by the target. */
  public NestedSet<LibraryToLink> getTransitiveNativeLibraries() {
    return getProviderAsNestedSet(
        JavaCcInfoProvider.class,
        x -> x.ccInfo().getCcNativeLibraryInfo().getTransitiveCcNativeLibraries());
  }

  @Override
  public Depset /*<LibraryToLink>*/ getTransitiveNativeLibrariesForStarlark() {
    return Depset.of(LibraryToLink.class, getTransitiveNativeLibraries());
  }

  @Override
  public CcInfoApi<Artifact> getCcLinkParamInfo() {
    return providerJavaCcInfo != null ? providerJavaCcInfo.ccInfo() : CcInfo.EMPTY;
  }

  @Override
  public JavaModuleFlagsProviderApi getJavaModuleFlagsInfo() {
    return providerModuleFlags == null ? JavaModuleFlagsProvider.EMPTY : providerModuleFlags;
  }

  @Override
  public Depset getTransitiveFullCompileJars() {
    return Depset.of(
        Artifact.class,
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider::transitiveFullCompileTimeJars));
  }

  @Override
  public Depset getCompileTimeJavaDependencies() {
    return Depset.of(
        Artifact.class,
        getProviderAsNestedSet(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider::compileTimeJavaDependencyArtifacts));
  }

  @Override
  public JavaPluginData plugins() {
    return providerJavaPlugin == null ? JavaPluginData.empty() : providerJavaPlugin.plugins();
  }

  @Override
  public JavaPluginData apiGeneratingPlugins() {
    return providerJavaPlugin == null
        ? JavaPluginData.empty()
        : providerJavaPlugin.apiGeneratingPlugins();
  }

  /** Returns all constraints set on the associated target. */
  public ImmutableList<String> getJavaConstraints() {
    return javaConstraints;
  }

  @Override
  public Sequence<String> getJavaConstraintsStarlark() {
    return StarlarkList.immutableCopyOf(javaConstraints);
  }

  /**
   * Gets Provider, check it for not null and call function to get NestedSet&lt;S&gt; from it.
   *
   * <p>Gets provider from map. If Provider is null, return default, empty, stabled ordered
   * NestedSet. If provider is not null, then delegates to mapper all responsibility to fetch
   * required NestedSet from provider.
   *
   * @param providerClass provider class. used as key to look up for provider.
   * @param mapper Function used to convert provider to NesteSet&lt;S&gt;
   * @param <P> type of Provider
   * @param <S> type of returned NestedSet items
   */
  private <P extends JavaInfoInternalProvider, S extends StarlarkValue>
      NestedSet<S> getProviderAsNestedSet(
          Class<P> providerClass, Function<P, NestedSet<S>> mapper) {

    P provider = getProvider(providerClass);
    if (provider == null) {
      return NestedSetBuilder.<S>stableOrder().build();
    }
    return mapper.apply(provider);
  }

  @Override
  public boolean equals(Object otherObject) {
    if (this == otherObject) {
      return true;
    }
    if (!(otherObject instanceof JavaInfo other)) {
      return false;
    }

    return Objects.equals(providerJavaCompilationArgs, other.providerJavaCompilationArgs)
        && Objects.equals(providerJavaSourceJars, other.providerJavaSourceJars)
        && Objects.equals(providerJavaRuleOutputJars, other.providerJavaRuleOutputJars)
        && Objects.equals(providerJavaGenJars, other.providerJavaGenJars)
        && Objects.equals(providerJavaCompilationInfo, other.providerJavaCompilationInfo)
        && Objects.equals(providerJavaCcInfo, other.providerJavaCcInfo)
        && Objects.equals(providerModuleFlags, other.providerModuleFlags)
        && Objects.equals(providerJavaPlugin, other.providerJavaPlugin);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        providerJavaCompilationArgs,
        providerJavaSourceJars,
        providerJavaRuleOutputJars,
        providerJavaGenJars,
        providerJavaCompilationInfo,
        providerJavaCcInfo,
        providerModuleFlags,
        providerJavaPlugin);
  }

  static final class RulesJavaJavaInfo extends JavaInfo {

    private RulesJavaJavaInfo(StructImpl javaInfo)
        throws EvalException, TypeException, RuleErrorException {
      super(javaInfo);
    }

    @Override
    public JavaInfoProvider getProvider() {
      return RULES_JAVA_PROVIDER;
    }
  }

  static final class WorkspaceJavaInfo extends JavaInfo {
    private WorkspaceJavaInfo(StructImpl javaInfo)
        throws EvalException, TypeException, RuleErrorException {
      super(javaInfo);
    }

    @Override
    public JavaInfoProvider getProvider() {
      return WORKSPACE_PROVIDER;
    }
  }

  /** Legacy Provider class for {@link JavaInfo} objects. */
  public static final class RulesJavaJavaInfoProvider extends JavaInfoProvider {
    private RulesJavaJavaInfoProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked("//java/private:java_info.bzl")));
    }

    @Override
    protected JavaInfo makeNewInstance(StructImpl info)
        throws RuleErrorException, TypeException, EvalException {
      return new RulesJavaJavaInfo(info);
    }
  }

  /** Legacy Provider class for {@link JavaInfo} objects in WORKSPACE mode. */
  public static final class WorkspaceJavaInfoProvider extends JavaInfoProvider {
    private WorkspaceJavaInfoProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked("@@rules_java//java/private:java_info.bzl")));
    }

    @Override
    protected JavaInfo makeNewInstance(StructImpl info)
        throws RuleErrorException, TypeException, EvalException {
      return new WorkspaceJavaInfo(info);
    }
  }

  /** Provider class for {@link JavaInfo} objects. */
  public static sealed class JavaInfoProvider extends StarlarkProviderWrapper<JavaInfo>
      implements Provider permits RulesJavaJavaInfoProvider, WorkspaceJavaInfoProvider {
    private JavaInfoProvider() {
      this(
          keyForBuild(
              Label.parseCanonicalUnchecked(
                  JavaSemantics.RULES_JAVA_PROVIDER_LABELS_PREFIX + "java/private:java_info.bzl")));
    }

    public JavaInfoProvider(BzlLoadValue.Key key) {
      super(key, STARLARK_NAME);
    }

    @Override
    public JavaInfo wrap(Info info) throws RuleErrorException {
      if (info instanceof JavaInfo javaInfo) {
        return javaInfo;
      } else if (info instanceof StructImpl) {
        try {
          return makeNewInstance((StructImpl) info);
        } catch (EvalException | TypeException e) {
          throw new RuleErrorException(e);
        }
      }
      throw new RuleErrorException("got " + Starlark.type(info) + ", wanted JavaInfo");
    }

    protected JavaInfo makeNewInstance(StructImpl info)
        throws RuleErrorException, TypeException, EvalException {
      return new JavaInfo(info);
    }

    @Override
    public boolean isExported() {
      return true;
    }

    @Override
    public String getPrintableName() {
      return STARLARK_NAME;
    }

    @Override
    public Location getLocation() {
      return Location.BUILTIN;
    }
  }

  // TODO: b/359437873 - generate with @AutoCodec.
  @Keep
  private static final class JavaInfoValueSharingCodec extends DeferredObjectCodec<JavaInfo> {

    @Override
    public Class<? extends JavaInfo> getEncodedClass() {
      return JavaInfo.class;
    }

    @Override
    public void serialize(SerializationContext context, JavaInfo obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(obj, null, JavaInfoCodec.INSTANCE, codedOut);
    }

    @Override
    public DeferredValue<JavaInfo> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      SimpleDeferredValue<JavaInfo> deferredValue = SimpleDeferredValue.create();
      context.getSharedValue(
          codedIn, null, JavaInfoCodec.INSTANCE, deferredValue, SimpleDeferredValue::set);
      return deferredValue;
    }
  }

  @Keep
  private static final class JavaInfoCodec extends DeferredObjectCodec<JavaInfo> {

    public static final JavaInfoCodec INSTANCE = new JavaInfoCodec();

    private final ImmutableList<FieldHandler> handlers;

    private JavaInfoCodec() {
      this.handlers =
          ImmutableList.copyOf(DynamicCodec.getFieldHandlerMap(JavaInfo.class).values());
    }

    @Override
    public boolean autoRegister() {
      // This is the internal implementation for the JavaInfo codec. Instead (auto) register
      // the external codec that does shared value serialization that uses this codec.
      return false;
    }

    @Override
    public Class<? extends JavaInfo> getEncodedClass() {
      return JavaInfo.class;
    }

    @Override
    public void serialize(SerializationContext context, JavaInfo obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      for (FieldHandler handler : handlers) {
        handler.serialize(context, codedOut, obj);
      }
    }

    @Override
    @SuppressWarnings("SunApi") // TODO: b/331765692 - delete this
    public DeferredValue<JavaInfo> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {

      JavaInfo obj;
      try {
        obj = (JavaInfo) unsafe().allocateInstance(JavaInfo.class);
      } catch (InstantiationException e) {
        throw new SerializationException("Could not instantiate JavaInfo with Unsafe", e);
      }

      for (FieldHandler handler : handlers) {
        handler.deserialize(context, codedIn, obj);
      }

      return () -> obj;
    }
  }
}
