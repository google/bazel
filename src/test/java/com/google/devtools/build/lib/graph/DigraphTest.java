// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.graph;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetData;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link Digraph}.
 */
@RunWith(JUnit4.class)
public class DigraphTest {

  class FakeTarget implements Target {

    private final Label label;

    FakeTarget(Label label) {
      this.label = label;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    @Override
    public Package getPackage() {
      return null;
    }

    @Override
    public String getTargetKind() {
      return null;
    }

    @Override
    public Rule getAssociatedRule() {
      return null;
    }

    @Override
    public License getLicense() {
      return null;
    }

    @Override
    public Location getLocation() {
      return null;
    }

    @Override
    public Set<DistributionType> getDistributions() {
      return null;
    }

    @Override
    public RuleVisibility getRawVisibility() {
      return null;
    }

    @Override
    public boolean isConfigurable() {
      return true;
    }

    @Override
    public TargetData reduceForSerialization() {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void testStableOrdering() throws Exception {
    Digraph<Target> digraph = new Digraph<>();
    FakeTarget a = new FakeTarget(Label.create("pkg", "a"));
    FakeTarget b = new FakeTarget(Label.create("pkg", "b"));
    FakeTarget c = new FakeTarget(Label.create("pkg", "c"));
    FakeTarget d = new FakeTarget(Label.create("pkg", "d"));
    FakeTarget e = new FakeTarget(Label.create("pkg", "e"));
    FakeTarget f = new FakeTarget(Label.create("pkg", "f"));
    FakeTarget g = new FakeTarget(Label.create("pkg", "g"));
    //    f
    // / | | \
    // c g e d
    //      / \
    //      a  b
    digraph.addEdge(f, c);
    digraph.addEdge(f, g);
    digraph.addEdge(d, a);
    digraph.addEdge(d, b);
    digraph.addEdge(f, e);
    digraph.addEdge(f, d);

    // Get them back in topological and, within a valid topological ordering, alphabetical order.
    Comparator<Target> comparator = new Comparator<Target>() {
      @Override
      public int compare(Target o1, Target o2) {
        return o1.getLabel().compareTo(o2.getLabel()) * -1;
      }
    };

    // Unwrap the Label from the Node<Target>, to make the final assert prettier.
    Function<? super Node<Target>, Label> unwrap =
        new Function<Node<Target>, Label>() {
          @Override
          public Label apply(Node<Target> node) {
            return node.getLabel().getLabel();
          }
        };
    List<Label> nodes = Lists.transform(digraph.getTopologicalOrder(comparator), unwrap);
    assertThat(nodes)
        .containsExactlyElementsIn(
            ImmutableList.of(
                Label.create("pkg", "f"),
                Label.create("pkg", "c"),
                Label.create("pkg", "d"),
                Label.create("pkg", "a"),
                Label.create("pkg", "b"),
                Label.create("pkg", "e"),
                Label.create("pkg", "g")));
  }
}
