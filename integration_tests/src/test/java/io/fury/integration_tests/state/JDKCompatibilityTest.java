/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.integration_tests.state;

import io.fury.Fury;
import io.fury.Language;
import io.fury.serializer.CompatibleMode;
import io.fury.test.bean.BeanA;
import io.fury.util.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JDKCompatibilityTest {
  @Test
  public void testAndPrepareData() throws IOException {
    {
      Fury fury = Fury.builder().withLanguage(Language.JAVA).build();
      BeanA beanA = BeanA.createBeanA(2);
      byte[] serialized = fury.serialize(beanA);
      Assert.assertEquals(fury.deserialize(serialized), beanA);
      Files.write(Paths.get("bean_schema_consistent" + Platform.JAVA_VERSION), serialized);
    }
    {
      Fury fury =
          Fury.builder()
              .withLanguage(Language.JAVA)
              .withCompatibleMode(CompatibleMode.COMPATIBLE)
              .build();
      BeanA beanA = BeanA.createBeanA(2);
      byte[] serialized = fury.serialize(beanA);
      Assert.assertEquals(fury.deserialize(serialized), beanA);
      Files.write(Paths.get("bean_schema_compatible" + Platform.JAVA_VERSION), serialized);
    }
  }

  @Test
  public void testSchemaConsist() throws IOException {
    BeanA beanA = BeanA.createBeanA(2);
    Fury fury = Fury.builder().withLanguage(Language.JAVA).build();
    File dir = new File(".");
    File[] files = dir.listFiles((d, name) -> name.startsWith("bean_schema_consistent"));
    assert files != null;
    check(beanA, fury, files);
  }

  @Test
  public void testSchemaCompatible() throws IOException {
    BeanA beanA = BeanA.createBeanA(2);
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    File dir = new File(".");
    File[] files = dir.listFiles((d, name) -> name.startsWith("bean_schema_compatible"));
    assert files != null;
    check(beanA, fury, files);
  }

  private static void check(BeanA beanA, Fury fury, File[] files) throws IOException {
    for (File file : files) {
      byte[] bytes = Files.readAllBytes(file.toPath());
      try {
        Object o = fury.deserialize(bytes);
        Assert.assertEquals(o, beanA);
      } catch (Throwable e) {
        throw new RuntimeException(
            "Check failed for " + file + " under JDK " + Platform.JAVA_VERSION, e);
      }
    }
  }
}
