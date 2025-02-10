// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/**
 * Scope of a {@link BuildOptions} is defined by the {@link Scope.ScopeType} and {@link
 * Scope.ScopeDefinition}.
 */
public class Scope {
  /**
   * Type of supported scopes. UNIVERSAL: Flags scoped with this type are allowded to propagate
   * everywhere. PROJECT: Flags scoped with this type are only allowed to propagate within the
   * project scope defined in the PROJECT.scl file by the user.
   */
  public enum ScopeType {
    UNIVERSAL,
    PROJECT;

    public static ScopeType valueOfIgnoreCase(String scopeType) {
      for (ScopeType scope : values()) {
        if (scope.name().equalsIgnoreCase(scopeType)) {
          return scope;
        }
      }
      throw new IllegalArgumentException("Invalid scope type: " + scopeType);
    }
  }

  /**
   * Definition of a scope. Users can define this in their PROJECT.scl file that is in the same
   * directory as the BUILD file where the scoped flags are defined or in a parent directory. This
   * is only relevant if the scope type is PROJECT.
   */
  public static class ScopeDefinition {
    private final ImmutableSet<String> ownedCodePaths;

    public ScopeDefinition(ImmutableSet<String> ownedCodePaths) {
      this.ownedCodePaths = ownedCodePaths;
    }

    public ImmutableSet<String> getOwnedCodePaths() {
      return ownedCodePaths;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("ownedCodePaths", ownedCodePaths).toString();
    }
  }

  ScopeType scopeType;
  @Nullable ScopeDefinition scopeDefinition;

  public Scope(ScopeType scopeType, @Nullable ScopeDefinition scopeDefinition) {
    this.scopeType = scopeType;
    this.scopeDefinition = scopeDefinition;
  }

  public ScopeType getScopeType() {
    return scopeType;
  }

  @Nullable
  public ScopeDefinition getScopeDefinition() {
    return scopeDefinition;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("scopeType", scopeType)
        .add("scopeDefinition", scopeDefinition)
        .toString();
  }
}
