/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.hops.fedplanner.rules;

import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class RulesetsCanonicalOpcodeLintTest {

  private static final Pattern LITERAL_SET_PATTERN =
      Pattern.compile("Set\\.of\\([^)]*\"[^\"]+\"");

  @Test
  public void noLiteralOpcodesInRulesets() throws Exception {
    Path root = Paths.get("src/main/java/org/apache/sysds/hops/fedplanner/rules");
    try (Stream<Path> files = Files.walk(root)) {
      files.filter(p -> p.toString().endsWith(".java"))
          .forEach(RulesetsCanonicalOpcodeLintTest::assertNoLiteralSet);
    }
  }

  private static void assertNoLiteralSet(Path file) {
    try {
      String content = Files.readString(file);
      Matcher matcher = LITERAL_SET_PATTERN.matcher(content);
      if (matcher.find()) {
        fail(file + " contains literal opcode in Set.of: " + matcher.group());
      }
    } catch (Exception ex) {
      fail("Failed to inspect " + file + ": " + ex.getMessage());
    }
  }
}
