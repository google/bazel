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
package com.google.devtools.build.lib.packages.util;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.skyframe.packages.PackageFactoryBuilderWithSkyframeForTesting;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestPackageFactoryBuilderFactory;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;

/** Create a mock client for the loading phase, as well as a configuration factory. */
public class LoadingMock {
  public static LoadingMock get() {
    return new LoadingMock();
  }

  public String getProductName() {
    return TestConstants.PRODUCT_NAME;
  }

  public PackageFactoryBuilderWithSkyframeForTesting getPackageFactoryBuilderForTesting(
      BlazeDirectories directories) {
    return (PackageFactoryBuilderWithSkyframeForTesting)
        TestPackageFactoryBuilderFactory.getInstance().builder(directories);
  }

  public ConfiguredRuleClassProvider createRuleClassProvider() {
    return TestRuleClassProvider.getRuleClassProvider();
  }
}
