/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fury.codegen;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.logging.Logger;
import org.apache.fury.logging.LoggerFactory;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.util.ClassLoaderUtils;
import org.apache.fury.util.GraalvmSupport;
import org.apache.fury.util.StringUtils;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.codehaus.commons.compiler.util.resource.MapResourceCreator;
import org.codehaus.commons.compiler.util.resource.MapResourceFinder;
import org.codehaus.commons.compiler.util.resource.Resource;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.Compiler;
import org.codehaus.janino.IType;
import org.codehaus.janino.util.ClassFile;

/** A util to compile code to bytecode and create classloader to load generated class. */
public class JaninoUtils {
  private static final Logger LOG = LoggerFactory.getLogger(JaninoUtils.class);

  public static Class<?> compileClass(
      ClassLoader loader, String pkg, String className, String code) {
    ByteArrayClassLoader classLoader = compile(loader, new CompileUnit(pkg, className, code));
    try {
      return classLoader.loadClass(StringUtils.isBlank(pkg) ? className : pkg + "." + className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static ByteArrayClassLoader compile(
      ClassLoader parentClassLoader, CompileUnit... compileUnits) {
    final Map<String, byte[]> classes = toBytecode(parentClassLoader, compileUnits);
    // Set up a class loader that finds and defined the generated classes.
    return new ByteArrayClassLoader(classes, parentClassLoader);
  }

  public static Map<String, byte[]> toBytecode(
      ClassLoader parentClassLoader, CompileUnit... compileUnits) {
    return toBytecode(parentClassLoader, CodeGenerator.getCodeDir(), compileUnits);
  }

  public static Map<String, byte[]> toBytecode(
      ClassLoader parentClassLoader, String codeDir, CompileUnit... compileUnits) {
    Map<String, String> codeMap = new HashMap<>();
    for (CompileUnit unit : compileUnits) {
      String stubFileName = unit.pkg.replace(".", "/") + "/" + unit.mainClassName + ".java";
      codeMap.put(stubFileName, unit.getCode());
      if (StringUtils.isNotBlank(codeDir)) {
        Path path = Paths.get(codeDir, stubFileName).toAbsolutePath();
        try {
          path.getParent().toFile().mkdirs();
          if (CodeGenerator.deleteCodeOnExit()) {
            path.toFile().deleteOnExit();
          } else {
            LOG.info("Write generate class {} to file {}", stubFileName, path);
          }
          Files.write(path, unit.getCode().getBytes());
        } catch (IOException e) {
          throw new RuntimeException(String.format("Write code file %s failed", path), e);
        }
      }
    }
    long startTime = System.nanoTime();

    try {
      Map<String, byte[]> result =
          GraalvmSupport.isGraalBuildtime()
              ? toBytecodeGraal(parentClassLoader, codeMap)
              : toBytecodeInternal(parentClassLoader, codeMap);
      long durationMs = (System.nanoTime() - startTime) / 1000_000;
      String classNames =
          Arrays.stream(compileUnits)
              .map(unit -> unit.mainClassName)
              .collect(Collectors.joining(", ", "[", "]"));
      LOG.info("Compile {} take {} ms", classNames, durationMs);
      return result;
    } catch (Exception e) {
      StringBuilder msgBuilder = new StringBuilder("Compile error: \n");
      for (int i = 0; i < compileUnits.length; i++) {
        CompileUnit unit = compileUnits[i];
        if (i != 0) {
          msgBuilder.append('\n');
        }
        String qualifiedName = unit.pkg + "." + unit.mainClassName;
        msgBuilder.append(qualifiedName).append(":\n");
        msgBuilder.append(CodeFormatter.format(unit.getCode()));
      }
      throw new CodegenException(msgBuilder.toString(), e);
    }
  }

  private static Map<String, byte[]> toBytecodeInternal(
      ClassLoader parentClassLoader, Map<String, String> codeMap) {
    MapResourceFinder sourceFinder = new MapResourceFinder();
    codeMap.forEach(sourceFinder::addResource);
    // Storage for generated bytecode
    final Map<String, byte[]> classes = new HashMap<>();
    // Set up the compiler.
    ClassLoaderIClassLoader classLoader = new ClassLoaderIClassLoader(parentClassLoader);
    Compiler compiler = new Compiler(sourceFinder, classLoader);
    compiler.setClassFileCreator(new MapResourceCreator(classes));
    compiler.setClassFileFinder(new MapResourceFinder(classes));

    // set debug flag to get source file names and line numbers for debug and stacktrace.
    // this is also the default behaviour for javac.
    compiler.setDebugSource(true);
    compiler.setDebugLines(true);

    // Compile all sources
    try {
      compiler.compile(sourceFinder.resources().toArray(new Resource[0]));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // See https://github.com/janino-compiler/janino/issues/173
    ReflectionUtils.setObjectFieldValue(classLoader, "classLoader", null);
    ReflectionUtils.setObjectFieldValue(classLoader, "loadedIClasses", new HashMap<>());
    return classes;
  }

  private static Map<String, byte[]> toBytecodeGraal(
      ClassLoader parentClassLoader, Map<String, String> codeMap) {
    try (ClassLoaderUtils.SelectedChildFirstURLClassLoader loader =
        new ClassLoaderUtils.SelectedChildFirstURLClassLoader(
            new URL[] {
              Resource.class.getProtectionDomain().getCodeSource().getLocation(),
              IType.class.getProtectionDomain().getCodeSource().getLocation(),
              JaninoUtils.class.getProtectionDomain().getCodeSource().getLocation()
            },
            parentClassLoader,
            c -> c.toLowerCase().contains("janino"))) {
      Method toBytecodeInternal =
          loader
              .loadClass(JaninoUtils.class.getName())
              .getDeclaredMethod("toBytecodeInternal", ClassLoader.class, Map.class);
      toBytecodeInternal.setAccessible(true);
      return (Map<String, byte[]>) toBytecodeInternal.invoke(null, parentClassLoader, codeMap);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class CodeStats {
    public final Map<String, Integer> methodsSize;
    public final int constPoolSize;

    public CodeStats(Map<String, Integer> methodsSize, int constPoolSize) {
      this.methodsSize = methodsSize;
      this.constPoolSize = constPoolSize;
    }

    @Override
    public String toString() {
      return "CodeStats{" + "methodsSize=" + methodsSize + ", constPoolSize=" + constPoolSize + '}';
    }
  }

  public static CodeStats getClassStats(Class<?> cls) {
    try (InputStream stream =
            cls.getResourceAsStream(ReflectionUtils.getClassNameWithoutPackage(cls) + ".class");
        BufferedInputStream bis = new BufferedInputStream(Objects.requireNonNull(stream))) {
      byte[] bytecodes = new byte[stream.available()];
      bis.read(bytecodes);
      return getClassStats(bytecodes);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static CodeStats getClassStats(byte[] classBytes) {
    try {
      ClassFile classFile = new ClassFile(new ByteArrayInputStream(classBytes));
      int constPoolSize = classFile.getConstantPoolSize();
      Class<?> codeAttrClass =
          Compiler.class
              .getClassLoader()
              .loadClass("org.codehaus.janino.util.ClassFile$CodeAttribute");
      Field codeAttrField = codeAttrClass.getDeclaredField("code");
      codeAttrField.setAccessible(true);
      Map<String, Integer> methodSizes = new LinkedHashMap<>();
      classFile.methodInfos.stream()
          .flatMap(
              m ->
                  Arrays.stream(m.getAttributes())
                      .filter(attr -> attr.getClass() == codeAttrClass)
                      .map(
                          attr -> {
                            try {
                              Object codeAttr = codeAttrField.get(attr);
                              int length = ((byte[]) codeAttr).length;
                              if (length > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) {
                                LOG.info(
                                    "Generated method too long to be JIT compiled:"
                                        + " class {} method {} size {}",
                                    classFile.getThisClassName(),
                                    m.getName(),
                                    length);
                                // } else if (length > CodeGenerator.DEFAULT_JVM_INLINE_METHOD_LIMIT
                                //     && !"<init>".equals(m.getName())) {
                                //   LOG.info(
                                //       "Generated method too long to be JIT inlined:"
                                //           + " class {} method {} size {}",
                                //       classFile.getThisClassName(),
                                //       m.getName(),
                                //       length);
                              }
                              return Tuple2.of(m.getName(), length);
                            } catch (IllegalAccessException e) {
                              throw new RuntimeException(e);
                            }
                          }))
          .sorted(Comparator.comparingInt(a -> -a.f1))
          .forEach(e -> methodSizes.put(e.f0, e.f1));
      return new CodeStats(methodSizes, constPoolSize);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
