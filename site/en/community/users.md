Project: /_project.yaml
Book: /_book.yaml

# Who's Using Bazel

{% include "_buttons.html" %}

Note: Using Bazel? You can add your company on
[StackShare](https://stackshare.io/bazel). To add yourself to this page,
contact [product@bazel.build](mailto:product@bazel.build).

This page lists companies and OSS projects that are known to use Bazel.
This does not constitute an endorsement.

## Companies using Bazel {:#companies-using-bazel}

### [acqio](https://acqio.com.br){: .external}

<img src="/community/images/acqio_logo.svg" width="150" align="right">

Acqio is a Fintech that provides payment products and services for small and
medium merchants. Acqio has a handful of monorepos and uses Bazel along with
Kubernetes to deliver fast and reliable microservices.

### [Adobe](https://www.adobe.com/){: .external}

<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/e/e0/Adobe_logo_and_wordmark_%282017%29.svg/440px-Adobe_logo_and_wordmark_%282017%29.svg.png" width="150" align="right">

Adobe has released Bazel [rules](https://github.com/adobe/rules_gitops){: .external} for
continuous, GitOps driven Kubernetes deployments.

### [Asana](https://asana.com){: .external}

<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/3/3b/Asana_logo.svg/256px-Asana_logo.svg.png" width="100" align="right">

Asana is a web and mobile application designed to help teams track their work.
In their own words:

> Bazel has increased reliability, stability, and speed for all of builds/tests
at Asana. We no longer need to clean because of incorrect caches.

### [Ascend.io](https://ascend.io)

Ascend is a Palo Alto startup that offers solutions for large data sets
analysis. Their motto is _Big data is hard. We make it easy_.

### [ASML](https://asml.com){: .external}

<img src="https://upload.wikimedia.org/wikipedia/en/6/6c/ASML_Holding_N.V._logo.svg" width="150" align=right>

ASML is an innovation leader in the semiconductor industry. We provide chipmakers
with everything they need – hardware, software and services – to mass produce
patterns on silicon through lithography.

### [Beeswax](https://www.beeswax.com/){: .external}

> Beeswax is a New York based startup that provides real time bidding as
service. Bazel powers their Jenkins based continuous integration and deployment
framework. Beeswax loves Bazel because it is blazingly fast, correct and well
supported across many languages and platforms.

### [Braintree](https://www.braintreepayments.com){: .external}

<img src="https://upload.wikimedia.org/wikipedia/commons/0/00/Braintree-logo1.png" width="150" align="right">

Braintree, a PayPal subsidiary, develops payment solutions for websites and
applications. They use Bazel for parts of their internal build and Paul Gross
even posted a
[nice piece about how their switch to Bazel went](https://www.pgrs.net/2015/09/01/migrating-from-gradle-to-bazel/).

### [Canva](https://www.canva.com/){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/b/bb/Canva_Logo.svg" width="90" align="right">

Canva leverages Bazel to manage its large polyglot codebase, which includes
Java, TypeScript, Scala, Python, and more. Migration to Bazel has delivered
significant developer and compute infrastructure efficiencies, for example 5-6x
decreases in average CI build times, and it continues to become the foundation
of fast, reproducible, and standardised software builds at the company.

### [CarGurus](https://www.cargurus.com){: .external}
<img src="https://www.cargurus.com/gfx/reskin/logos/logo_CarGurus.svg" width="150" align="right">

CarGurus is on a mission to build the world's most trusted and transparent
automotive marketplace and uses Bazel to build their polyglot monorepo.

### [Compass](https://www.compass.com){: .external}

Compass is a tech-driven real estate platform. With an elite team of real
estate, technology and business professionals, we aim to be the best and most
trusted source for home seekers.

### [Databricks](https://databricks.com){: .external}

<img src="https://databricks.com/wp-content/uploads/2021/10/db-nav-logo.svg" width="100" align="right">
Databricks provides cloud-based integrated workspaces based on Apache Spark™.

> The Databricks codebase is a Monorepo, containing the Scala code that powers
most of our services, Javascript for front-end UI, Python for scripting,
Jsonnet to configure our infrastructure, and much more [...] Even though our
monorepo contains a million lines of Scala, working with code within is fast
and snappy.
([Speedy Scala Builds with Bazel at Databricks](https://databricks.com/blog/2019/02/27/speedy-scala-builds-with-bazel-at-databricks.html))

### [Dataform](https://dataform.co){: .external}

Dataform provides scalable analytics for data teams. They maintain a handful of
NPM packages and a documentation site in one single monorepo and they do it all
with Bazel.

After the migration to Bazel, they
[reported many benefits](https://github.com/bazelbuild/rules_nodejs#user-testimonials){: .external},
including:

> * Faster CI: we enabled the remote build caching which has reduced our average build time from 30 minutes to 5 (for the entire repository).
> * Improvements to local development: no more random bash scripts that you forget to run, incremental builds reduced to seconds from minutes
> * Developer setup time: New engineers can build all our code with just 3 dependencies - bazel, docker and the JVM. The last engineer to join our team managed to build all our code in < 30 minutes on a brand new, empty laptop

### [Deep Silver FISHLABS](https://www.dsfishlabs.com){: .external}
Deep Silver FISHLABS is a developer of high-end 3D games. They use Bazel with
C++/Python/Go/C as a base for their internal build tooling and especially for
baking and deploying all their 3D Assets.

### [Dropbox](https://www.dropbox.com/){: .external}
<img src="/community/images/dropbox.png" width="150" align="right">
At Dropbox, Bazel is a key component to our distributed build and test
environment. We use Bazel to combine TypeScript/Python/Go/C/Rust into reliable
production releases.

### [Engel & Völkers](https://www.engelvoelkers.com){: .external}

Engel & Völkers AG is a privately owned German company that, via a series of
franchised offices, provides services related to real estate transactions.

> One of our internal project has seen a decrease of compilation time from 11
minutes to roughly 1 minute, this was an impressive achievement and we are
currently working on bringing Bazel to more projects.
([Experimenting with Google Cloud Build and Bazel](https://www.engelvoelkers.com/en/tech/engineering/software-engineering/experimenting-with-google-cloud-build-and-bazel/)){: .external}

### [Etsy](https://www.etsy.com/){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/a/aa/Etsy_logo_lg_rgb.png" width="150" align="right">

Etsy is an e-commerce website focused on handmade or vintage items and supplies,
as well as unique factory-manufactured items.

They use Bazel to build and test its Java-based search platform. Bazel produces
both packages for bare metal servers and repeatable Docker images.

### [Evertz.io](https://www.evertz.io/){: .external}

Evertz.io is a multi-tenant, serverless SaaS platform for offering cost
effective, multi-regional services worldwide to the Broadcast Media Industry,
created by [Evertz Microsystems](https://en.wikipedia.org/wiki/Evertz_Microsystems).

The website is fully built and deployed with an Angular and Bazel workflow
([source](https://twitter.com/MattMackay/status/1113947685508341762){: .external}).

### [FINDMINE](http://www.findmine.com){: .external}
<img src="https://www.findmine.com/static/assets/landpage/findmine-color-logo.png" width="150" align="right">

FINDMINE is a automation technology for the retail industry that uses machine
learning to scale the currently manual and tedious process of product curation.
We use Bazel to mechanize our entire python package building, testing, and
deployment process.

### [Flexport](https://www.flexport.com/){: .external}

Flexport is a tech-enabled global freight forwarder; our mission is to make
global trade easier for everyone. At Flexport, we use Bazel to build/test our
Java/JavaScript services and client libraries and to generate Java and Ruby
code from protobuf definitions.
[Read about how we run individual JUnit 5 tests in isolation with Bazel.](https://flexport.engineering/connecting-bazel-and-junit5-by-transforming-arguments-46440c6ea068)

### [Foursquare](https://foursquare.com){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/9/99/FSQ_logo.png" width="150" align="right">

Foursquare's mission is to create technology that constructs meaningful
bridges between digital spaces and physical places. We manage millions of
lines of primarily Scala and Python code powering data-intensive
applications, including complex codegen and container build processes, with
Bazel.

### [GermanTechJobs](https://germantechjobs.de){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/9/98/GermanTechJobs_Logo.png" width="150" align="right">

Bazel has simplified our workflows 10-fold and enabled shipping features at
scale.

### [Google](https://google.com){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/2/2f/Google_2015_logo.svg" width="150" align="right">

Bazel was designed to be able to scale to Google's needs and meet Google's
requirements of reproducibility and platform/language support. All software at
Google is built using Bazel. Google uses Bazel and its rules for millions of
builds every day.

### [Huawei](http://www.huawei.com/){: .external}

> Huawei Technologies is using Bazel in about 30 projects, they are Java/Scala/Go
projects, except for Go projects, others originally were built by Maven. We
write a simple tool to translate a Maven-built project into Bazel-built one.
More and more projects will use Bazel in recent future.

### [IMC Trading](https://imc.com){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/1/17/IMC_Logo.svg" width="150" align="right">

> IMC is a global proprietary trading firm and market maker headquarted in
Amsterdam. We are using Bazel to continuously build and test our
Java/C++/Python/SystemVerilog projects.

### [Improbable.io](https://improbable.io/){: .external}

Improbable.io develops SpatialOS, a distributed operating system that enables
creating huge simulations inhabited by millions of complex entities.

### [Interaxon](https://www.choosemuse.com/){: .external}

InteraXon is a thought-controlled computing firm that creates hardware and
software platforms to convert brainwaves into digital signals.

### [Jupiter](https://jupiter.co/){: .external}

Jupiter is a company that provides delivery of groceries and household
essentials every week.

They use Bazel in their backend code, specifically to compile protos and Kotlin
to JVM binaries, using remote caching.
([source](https://starship.jupiter.co/jupiter-stack/))

### [Just](https://gojust.com/){: .external}

Just is an enterprise financial technology company, headquartered in Norway,
creating software solutions to transform how global corporate treasurers manage
risk and liquidity. Their entire application stack is built with Bazel.

### [Line](https://line.me/)

Line provides an app for instant communications, which is the most popular
messaging application in Japan.
They use Bazel on their codebase consisting of about 60% Swift and 40%
C/C++/Objective-C/Objective-C++
([source](https://twitter.com/thi_dt/status/1253334262020886532){: .external}).

> After switching to Bazel, we were able to achieve a huge improvement in the
build times. This brought a significant improvement in the turn-around time
during a QA period. Distributing a new build to our testers no longer means
another hour waiting for building and testing.
([Improving Build Performance of LINE for iOS with Bazel](https://engineering.linecorp.com/en/blog/improving-build-performance-line-ios-bazel/){: .external})

### [LingoChamp](https://www.liulishuo.com/en){: .external}

<img src="/community/images/liulishuo.png" width="100" align="right">
LingoChamp provides professional solutions to English learners. We use Bazel
for our go, java and python projects.

### [LinkedIn](https://linkedin.com/){: .external}

<img src="/community/images/Linkedin-Logo.png" width="100" align="right">
LinkedIn, a subsidiary of Microsoft, is the world’s largest professional social
network. LinkedIn uses Bazel for building its iOS Apps.

### [Lucid Software](https://lucid.co/){: .external}

<img src="/community/images/Lucid_Software-logo.svg" width="150" align="right">

Lucid Software is a leader in visual collaboration, helping teams see and build the
future from idea to reality. With its products—[Lucidchart](https://www.lucidchart.com/),
[Lucidspark](https://lucidspark.com/), and [Lucidscale](https://lucidscale.com/)—teams
can align around a shared vision, clarify complexity, and collaborate visually, no
matter where they’re located.

Lucid uses Bazel to build millions of lines of Scala and TypeScript.
Migrating to Bazel has tremendously sped up its builds, reduced external
dependencies on the build environment, and simplified developers' experience
with the build system. Bazel has improved developer productivity at Lucid and
unlocked further growth.

### [Lyft](https://www.lyft.com/){: .external}

Lyft is using Bazel for their iOS ([source](https://twitter.com/SmileyKeith/status/1116486751806033920)) and Android Apps.

### [Meetup](http://www.meetup.com/){: .external}

Meetup is an online social networking portal that facilitates offline group
meetings.
The Meetup engineering team contributes to
[rules_scala](https://github.com/bazelbuild/rules_scala){: .external} and is the
maintainer of [rules_avro](https://github.com/meetup/rules_avro){: .external}
and [rules_openapi](https://github.com/meetup/rules_openapi){: .external}.


### [Nvidia](https://www.nvidia.com/){: .external}

> At Nvidia we have been using dazel(docker bazel) for python to work around
some of bazel's python short comings. Everything else runs in normal bazel
(Mostly Go / Scala/ C++/ Cuda)
([source](https://twitter.com/rwhitcomb/status/1080887723433447424){: .external})


### [Peloton Technology](http://www.peloton-tech.com){: .external}

Peloton Technology is an automated vehicle technology company that tackles truck
accidents and fuel use. They use Bazel to _enable reliable builds for automotive
safety systems_.

### [Pigweed](https://pigweed.dev){: .external}

<img src="https://pigweed.dev/_static/pw_logo.svg" width="100" align="right">

Pigweed is an open-source solution for sustained, robust, and rapid embedded
product development for large teams. Pigweed has shipped in millions of
devices, including Google's suite of Pixel devices, Nest thermostats,
[satellites](https://www.spinlaunch.com/), and [autonomous aerial
drones](https://www.flyzipline.com/).

Pigweed [uses Bazel as its primary build
system](https://pigweed.dev/seed/0111-build-systems.html). The [Bazel for
Embedded][pw-bazel-great] blog post discusses why we think it's a great build
system for embedded projects!

[pw-bazel-great]: https://blog.bazel.build/2024/08/08/bazel-for-embedded.html#why-bazel-for-embedded

### [Pinterest](https://www.pinterest.com/){: .external}

<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/3/35/Pinterest_Logo.svg/200px-Pinterest_Logo.svg.png" width="150" align="right">

Pinterest is the world’s catalog of ideas. They use Bazel to build various
backend services (Java/C++) and the iOS application (Objective-C/C++).

> We identified Bazel was the best fit for our goals to build a foundation for
an order of magnitude improvement in performance, eliminate variability in
build environments and adopt incrementally. As a result, we’re now shipping all
our iOS releases using Bazel.
[Developing fast & reliable iOS builds at Pinterest](https://medium.com/@Pinterest_Engineering/developing-fast-reliable-ios-builds-at-pinterest-part-one-cb1810407b92)

### [PubRef](https://github.com/pubref){: .external}

PubRef is an emerging scientific publishing platform.  They use Bazel with
[rules_closure](https://github.com/bazelbuild/rules_closure){: .external} to build the
frontend, native java rules to build the main backend,
[rules_go](https://github.com/bazelbuild/rules_go){: .external},
[rules_node](https://github.com/pubref/rules_node){: .external}, and
[rules_kotlin](https://github.com/pubref/rules_kotlin){: .external} to build assorted
backend services.  [rules_protobuf](https://github.com/pubref/rules_protobuf){: .external} is
used to assist with gRPC-based communication between backend services.
PubRef.org is based in Boulder, CO.

### [Redfin](https://redfin.com/){: .external}
Redfin is a next-generation real estate brokerage with full-service local
agents. They use Bazel to build and deploy the website and various backend
services.

> With the conversion mostly behind us, things are greatly improved! Our CI
builds are faster (*way* faster: they used to take 40–90 minutes, and now dev
builds average 5–6 minutes). Reliability is far higher, too. This is harder to
quantify, but the shift from unexplained build failures being something that
“just happens” to being viewed as real problems to be solved has put us on a
virtuous cycle of ever-increasing reliability.
([We Switched from Maven to Bazel and Builds Got 10x Faster](https://redfin.engineering/we-switched-from-maven-to-bazel-and-builds-got-10x-faster-b265a7845854))

### [Ritual](https://ritual.co){: .external}
<img src="https://lh3.googleusercontent.com/7Ir6j25ROnsXhtQXveOzup33cizxLf-TiifSC1cI6op0bQVB-WePmPjJOfXUBQ0L3KpkheObAiS28e-TS8hZtDzxOIc" width="150" align="right">

Ritual is a mobile pick up app, connecting restaurants with customers to offer
a simple, time-saving tool to get the food and beverages they want, without the
wait. Ritual uses Bazel for their backend services.

### [Snap](https://www.snap.com/en-US/){: .external}

Snap, the developer of Snapchat messaging app, has migrated from Buck to Bazel
in 2020 ([source](https://twitter.com/wew/status/1326957862816509953){: .external}). For more
details about their process, see their [engineering blog](https://eng.snap.com/blog/){: .external}.

### [Stripe](https://stripe.com){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/b/ba/Stripe_Logo%2C_revised_2016.svg/320px-Stripe_Logo%2C_revised_2016.svg.png" width="150" align="right">

Stripe provides mobile payment solutions. They use Bazel in their build and test pipelines, as detailed in their [engineering blog](https://stripe.com/blog/fast-secure-builds-choose-two){: .external}.

### [Tinder](https://tinder.com){: .external}
<img src="https://policies.tinder.com/static/b0327365f4c0a31c4337157c10e9fadf/c1b63/tinder_full_color_watermark.png" width="150" align="right">

Tinder migrated its iOS app from CocoaPods to Bazel
in 2021 ([source](https://medium.com/tinder/bazel-hermetic-toolchain-and-tooling-migration-c244dc0d3ae){: .external}).

### [Tink](https://tink.com/){: .external}
<img src="https://cdn.tink.se/tink-logos/LOW/Tink_Black.png" width="150" align="right">

Tink is a european fintech, building the best way to connect to banks across
Europe.

They are using Bazel to build their backend services from a polyglot monorepo.
Engineers at Tink are organizing the [bazel build //stockholm/...](https://www.meetup.com/BazelSTHLM/)
meetup group.

### [Tokopedia](https://www.tokopedia.com/){: .external}

Tokopedia is an Indonesian technology company specializing in e-commerce, with
over 90 million monthly active users and over 7 million merchants on the
platform.

They wrote the article
[How Tokopedia Achieved 1000% Faster iOS Build Time](https://medium.com/tokopedia-engineering/how-tokopedia-achieved-1000-faster-ios-build-time-7664b2d8ae5),
where they explain how Bazel sped up their builds. The build duration went from
55 minutes to 10 minutes by using Bazel, and down to 5 minutes with remote
caching.

### [Trunk.io](https://trunk.io/merge/trunk-merge-and-bazel){: .external}
<img src="/community/images/trunk-logo-dark.svg" width="150" align="right">

Trunk is a San Francisco-based company backed by Andreessen Horowitz and Initialized Capital. Trunk offers a powerful pull request merge service with first-class support for the Bazel build system. By leveraging Bazel's understanding of dependencies within a codebase, Trunk's merge service intelligently creates parallel merge lanes, allowing independent changes to be tested and merged simultaneously.

> Trunk’s internal monorepo builds modern C++ 20 and typescript all while leveraging bazel graph knowledge to selectively test and merge code.

### [Twitter](https://twitter.com/){: .external}

Twitter has made the decision to migrate from Pants to Bazel as their primary
build tool
([source](https://groups.google.com/forum/#!msg/pants-devel/PHVIbVDLhx8/LpSKIP5cAwAJ)).

### [Two Sigma](https://www.twosigma.com/){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/c/c6/Two_Sigma_logo.svg/2880px-Two_Sigma_logo.svg.png" width="150" align="right">

Two Sigma is a New York-headquartered technology company dedicated to finding
value in the world’s data.

### [TypeDB](https://typedb.com){: .external}
<img src="/community/images/typedb.png" alt="TypeDB Logo" width="150" align="right">

TypeDB is a database technology that can be used to intuitively model
interconnected data. Through its type-theoretic and polymorphic query language,
TypeQL, the data can be accessed with simple, human-readable queries that run at
lightspeed.

Bazel enables the TypeDB team to build a highly-orchestrated CI and distribution
pipeline that manages many repositories in a wide variety of languages, and
deploys to numerous platforms seamlessly. The TypeDB team has also released
Bazel rules for assembling and deploying software distributions.

### [Uber](https://www.uber.com){: .external}

Uber is a ride-hailing company. With 900 active developers, Uber’s Go monorepo
is likely one of the largest Go repositories using Bazel. See the article
[Building Uber’s Go Monorepo with Bazel](https://eng.uber.com/go-monorepo-bazel/)
to learn more about their experience.

### [Uber Advanced Technologies Group](https://www.uber.com/info/atg/){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/6/62/Uber_logo.svg/220px-Uber_logo.svg.png" width="150" align="right">

Uber Advanced Technologies Group is focused on autonomous vehicle efforts at
Uber, including trucking/freight and autonomous ride sharing. The organization
uses Bazel as its primary build system.

### [Vistar Media](http://vistarmedia.com){: .external}
Vistar Media is an advertising platform that enables brands to reach consumers
based on their behavior in the physical world. Their engineering team is
primarily based out of Philadelphia and is using Bazel for builds, deploys, to
speed up testing, and to consolidate repositories written with a variety of
different technologies.

### [VMware](https://www.vmware.com/){: .external}
VMware uses Bazel to produce deterministic, reliable builds while developing
innovative products for their customers.

### [Wix](https://www.wix.com/){: .external}

Wix is a cloud-based web development platform. Their backend uses Java and Scala
code. They use remote execution with Google Cloud Build.

> We have seen about 5 times faster clean builds when running with bazel remote
execution which utilizes bazel’s great build/test parallelism capabilities when
it dispatches build/test actions to a worker farm. Average build times are more
than 10 times faster due to the utilization of bazel’s aggressive caching
mechanism.
([Migrating to Bazel from Maven or Gradle? 5 crucial questions you should ask yourself](https://medium.com/wix-engineering/migrating-to-bazel-from-maven-or-gradle-5-crucial-questions-you-should-ask-yourself-f23ac6bca070){: .external})

### [Zenly](https://zen.ly/){: .external}

Zenly is a live map of your friends and family. It’s the most fun way to meet up
— or just see what’s up! — so you can feel together, even when you're apart.


***

## Open source projects using Bazel {:#open-source-projects-using-Bazel}

### [Abseil](https://abseil.io/){: .external}

Abseil is an open-source collection of C++ code (compliant to C++11) designed
to augment the C++ standard library.

### [Angular](https://angular.io){: .external}

<img src="https://upload.wikimedia.org/wikipedia/commons/c/cf/Angular_full_color_logo.svg" width="120" align="right">

Angular is a popular web framework.
Angular is [built with Bazel](https://github.com/angular/angular/blob/master/docs/BAZEL.md).

### [Apollo](https://github.com/ApolloAuto/apollo)

Apollo is a high performance, flexible architecture which accelerates the
development, testing, and deployment of Autonomous Vehicles.

### [brpc](https://github.com/brpc/brpc){: .external}

An industrial-grade RPC framework used throughout Baidu, with 1,000,000+
instances(not counting clients) and thousands kinds of services, called
"baidu-rpc" inside Baidu.

### [cert-manager](https://github.com/jetstack/cert-manager){: .external}

cert-manager is a Kubernetes add-on to automate the management and issuance of
TLS certificates from various issuing sources. It will ensure certificates are
valid and up to date periodically, and attempt to renew certificates at an
appropriate time before expiry.

### [CallBuilder](https://github.com/google/CallBuilder){: .external}

A Java code generator that allows you to create a builder by writing one
function.

### [CPPItertools](https://github.com/ryanhaining/cppitertools){: .external}

C++ library providing range-based for loop add-ons inspired by the Python
builtins and itertools library. Like itertools and the Python3 builtins, this
library uses lazy evaluation wherever possible.

### [Copybara](https://github.com/google/copybara){: .external}

Copybara is a tool for transforming and moving code between repositories.

### [Dagger](https://google.github.io/dagger/){: .external}

Dagger is a fully static, compile-time dependency injection framework for both
Java and Android.

### [DAML](https://github.com/digital-asset/daml){: .external}

DAML is a smart contract language for building future-proof distributed
applications on a safe, privacy-aware runtime.

### [DeepMind Lab](https://github.com/deepmind/lab){: .external}

A customisable 3D platform for agent-based AI research.

### [Drake](https://github.com/RobotLocomotion/drake){: .external}

Drake is a C++ toolbox started at MIT and now led by the Toyota Research
Institute. It is a collection of tools for analyzing the dynamics of our robots
and building control systems for them, with a heavy emphasis on
optimization-based design/analysis.

### [Envoy](https://github.com/lyft/envoy){: .external}

C++ L7 proxy and communication bus

### [Error Prone](https://github.com/google/error-prone){: .external}

Catches common Java mistakes as compile-time errors. (Migration to Bazel is in
progress.)

### [Extensible Service Proxy](https://github.com/cloudendpoints/esp){: .external}

Extensible Service Proxy, a.k.a. ESP is a proxy which enables API management
capabilities for JSON/REST or gRPC API services. The current implementation is
based on an NGINX HTTP reverse proxy server.

### [FFruit](https://gitlab.com/perezd/ffruit/){: .external}

FFruit is a free & open source Android application to the popular service
[Falling Fruit](https://fallingfruit.org){: .external}.

### [Gerrit Code Review](https://gerritcodereview.com){: .external}

Gerrit is a code review and project management tool for Git based projects.

### [Gitiles](https://gerrit.googlesource.com/gitiles/){: .external}

Gitiles is a simple repository browser for Git repositories, built on JGit.

### [Grakn](https://github.com/graknlabs/grakn){: .external}

Grakn (https://grakn.ai/) is the knowledge graph engine to organise complex
networks of data and make it queryable.

### [GRPC](http://www.grpc.io){: .external}
A language-and-platform-neutral remote procedure call system.
(Bazel is a supported, although not primary, build system.)

### [gVisor](https://github.com/google/gvisor){: .external}
gVisor is a container runtime sandbox.

### [Guetzli](https://github.com/google/guetzli/){: .external}

Guetzli is a JPEG encoder that aims for excellent compression density at high
visual quality.

### [Gulava](http://www.github.com/google/gulava/){: .external}

A Java code generator that lets you write Prolog-style predicates and use them
seamlessly from normal Java code.

### [Heron](https://github.com/apache/incubator-heron){: .external}

Heron is a realtime, distributed, fault-tolerant stream processing engine from
Twitter.

### [Internet Computer Protocol](https://internetcomputer.org/){: .external}

<img src="https://internetcomputer.org/img/IC_logo_horizontal_white.svg" width="120" align="right">

The Internet Computer Protocol is a publicly available blockchain network that
enables replicated execution of general-purpose computation, serving hundreds
of thousands of applications and their users.

### [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer){: .external}

<img src="https://www.code-intelligence.com/hubfs/Logos/CI%20Logos/Jazzer_einfach.png" width="120" align="right">

Jazzer is a fuzzer for Java and other JVM-based languages that integrates with JUnit 5.

### [JGit](https://eclipse.org/jgit/){: .external}

JGit is a lightweight, pure Java library implementing the Git version control
system.

### [Jsonnet](https://jsonnet.org/){: .external}

An elegant, formally-specified config generation language for JSON.
(Bazel is a supported build system.)

### [Kubernetes](https://github.com/kubernetes/kubernetes){: .external}

<img src="https://raw.githubusercontent.com/kubernetes/kubernetes/master/logo/logo.png" width="80" align="right">
Kubernetes is an open source system for managing containerized applications
across multiple hosts, providing basic mechanisms for deployment, maintenance,
and scaling of applications.

### [Kythe](https://github.com/google/kythe){: .external}

An ecosystem for building tools that work with code.

### [ls-lint](https://github.com/loeffel-io/ls-lint){: .external}

<img src="https://raw.githubusercontent.com/loeffel-io/ls-lint/master/assets/logo/ls-lint.png" width="75" align="right">

An extremely fast directory and filename linter - Bring some structure to your
project file system.

### [Nomulus](https://github.com/google/nomulus){: .external}

Top-level domain name registry service on Google App Engine.

### [ONOS : Open Network Operating System](https://github.com/opennetworkinglab/onos){: .external}

<img src="https://upload.wikimedia.org/wikipedia/en/thumb/d/d3/Logo_for_the_ONOS_open_source_project.png/175px-Logo_for_the_ONOS_open_source_project.png" width="120" align="right">
ONOS is the only SDN controller platform that supports the transition from
legacy “brown field” networks to SDN “green field” networks. This enables
exciting new capabilities, and disruptive deployment and operational cost points
for network operators.

### [PetitParser for Java](https://github.com/petitparser/java-petitparser){: .external}

Grammars for programming languages are traditionally specified statically.
They are hard to compose and reuse due to ambiguities that inevitably arise.
PetitParser combines ideas from scannnerless parsing, parser combinators,
parsing expression grammars and packrat parsers to model grammars and parsers
as objects that can be reconfigured dynamically.

### [PlaidML](https://github.com/plaidml/plaidml){: .external}

PlaidML is a framework for making deep learning work everywhere.

### [Project V](https://www.v2ray.com/){: .external}

<img src="https://www.v2ray.com/resources/v2ray_1024.png" width="100" align="right">
Project V is a set of tools to help you build your own privacy network over
internet.

### [Prysmatic Labs Ethereum 2.0 Implementation](https://github.com/prysmaticlabs/prysm){: .external}

Prysm is a sharding client for Ethereum 2.0, a blockchain-based distributed
computing platform.

### [Ray](https://github.com/ray-project/ray){: .external}

Ray is a flexible, high-performance distributed execution framework.

### [Resty](https://github.com/go-resty/resty){: .external}

Resty is a Simple HTTP and REST client library for Go (inspired by Ruby
rest-client).

### [Roughtime](https://roughtime.googlesource.com/roughtime){: .external}

Roughtime is a project that aims to provide secure time synchronisation.

### [Selenium](https://github.com/SeleniumHQ/selenium){: .external}

Selenium is a portable framework for testing web applications.

### [Semantic](https://github.com/github/semantic){: .external}

Semantic is a Haskell library and command line tool for parsing, analyzing, and
comparing source code. It is developed by GitHub (and used for example for the
code navigation).

### [Served](https://github.com/meltwater/served){: .external}

Served is a C++ library for building high performance RESTful web servers.

### [Sonnet](https://github.com/deepmind/sonnet){: .external}

Sonnet is a library built on top of TensorFlow for building complex neural
networks.

### [Sorbet](https://github.com/sorbet/sorbet){: .external}

Sorbet is a fast, powerful type checker for a subset of Ruby. It scales to
codebases with millions of lines of code and can be adopted incrementally.

### [Spotify](https://spotify.com){: .external}

Spotify is using Bazel to build their iOS and Android Apps ([source](https://twitter.com/BalestraPatrick/status/1573355078995566594)).

### [Tink](https://github.com/google/tink){: .external}

Tink is a multi-language, cross-platform, open source library that provides
cryptographic APIs that are secure, easy to use correctly, and hard(er) to
misuse.

### [TensorFlow](http://tensorflow.org){: .external}
<img src="https://upload.wikimedia.org/wikipedia/commons/a/a4/TensorFlowLogo.png" width="150" align="right">

An open source software library for machine intelligence.

### [Turbo Santa](https://github.com/turbo-santa/turbo-santa-common){: .external}

A platform-independent GameBoy emulator.

### [Wycheproof](https://github.com/google/wycheproof){: .external}

Project Wycheproof tests crypto libraries against known attacks.

### [XIOSim](https://github.com/s-kanev/XIOSim){: .external}

XIOSim is a detailed user-mode microarchitectural simulator for the x86
architecture.

### [ZhihuDailyPurify](https://github.com/izzyleung/ZhihuDailyPurify){: .external}

ZhihuDailyPurify is a light weight version of Zhihu Daily, a Chinese
question-and-answer webs.