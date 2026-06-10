/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.tasks

import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.builder.utils.resolveNewDirectories
import io.bazel.kotlin.model.CompilationTaskInfo
import io.bazel.kotlin.model.JvmCompilationTask
import io.bazel.kotlin.model.Platform
import io.bazel.kotlin.model.RuleKind
import io.bazel.worker.WorkerContext
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("MemberVisibilityCanBePrivate")
class KotlinBuilder
  @Inject
  internal constructor(
    private val jvmTaskExecutor: KotlinJvmTaskExecutor,
  ) {
    companion object {
      @JvmStatic
      private val FLAGFILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

      /**
       * Resolves a request-relative input/output path against the multiplex sandbox directory.
       *
       * Under path mapping with multiplex sandboxing, Bazel strips the configuration prefix from
       * paths (e.g. `bazel-out/cfg/bin/...`) and materializes the actual files inside the request's
       * `sandbox_dir`; the worker is responsible for prefixing them (see worker_protocol.proto).
       * When [sandboxDir] is null (no sandbox) or the path is empty/already absolute, the path is
       * returned unchanged.
       */
      @JvmStatic
      internal fun resolveInSandbox(
        sandboxDir: Path?,
        path: String,
      ): String =
        if (sandboxDir != null && path.isNotEmpty() && !Path.of(path).isAbsolute) {
          sandboxDir.resolve(path).toString()
        } else {
          path
        }

      enum class KotlinBuilderFlags(
        override val flag: String,
      ) : Flag {
        TARGET_LABEL("--target_label"),
        CLASSPATH("--classpath"),
        DIRECT_DEPENDENCIES("--direct_dependencies"),
        DEPS_ARTIFACTS("--deps_artifacts"),
        SOURCES("--sources"),
        SOURCE_JARS("--source_jars"),
        PROCESSOR_PATH("--processorpath"),
        PROCESSORS("--processors"),
        STUBS_PLUGIN_OPTIONS("--stubs_plugin_options"),
        STUBS_PLUGIN_CLASS_PATH("--stubs_plugin_classpath"),
        COMPILER_PLUGIN_OPTIONS("--compiler_plugin_options"),
        COMPILER_PLUGIN_CLASS_PATH("--compiler_plugin_classpath"),
        OUTPUT("--output"),
        RULE_KIND("--rule_kind"),
        MODULE_NAME("--kotlin_module_name"),
        PASSTHROUGH_FLAGS("--kotlin_passthrough_flags"),
        API_VERSION("--kotlin_api_version"),
        LANGUAGE_VERSION("--kotlin_language_version"),
        JVM_TARGET("--kotlin_jvm_target"),
        OUTPUT_SRCJAR("--kotlin_output_srcjar"),
        GENERATED_CLASSDIR("--kotlin_generated_classdir"),
        FRIEND_PATHS("--kotlin_friend_paths"),
        OUTPUT_JDEPS("--kotlin_output_jdeps"),
        DEBUG("--kotlin_debug_tags"),
        TASK_ID("--kotlin_task_id"),
        ABI_JAR("--abi_jar"),
        ABI_JAR_INTERNAL_AS_PRIVATE("--treat_internal_as_private_in_abi_jar"),
        ABI_JAR_REMOVE_PRIVATE_CLASSES("--remove_private_classes_in_abi_jar"),
        ABI_JAR_REMOVE_DEBUG_INFO("--remove_debug_info_in_abi_jar"),
        ABI_JAR_PRESERVE_DECLARATION_ORDER("--preserve_declaration_order"),
        ABI_JAR_REMOVE_DATA_CLASS_COPY_IF_CONSTRUCTOR_IS_PRIVATE("--remove_data_class_copy_if_constructor_is_private"),
        GENERATED_JAVA_SRC_JAR("--generated_java_srcjar"),
        GENERATED_JAVA_STUB_JAR("--kapt_generated_stub_jar"),
        GENERATED_CLASS_JAR("--kapt_generated_class_jar"),
        BUILD_KOTLIN("--build_kotlin"),
        STRICT_KOTLIN_DEPS("--strict_kotlin_deps"),
        REDUCED_CLASSPATH_MODE("--reduced_classpath_mode"),
        INSTRUMENT_COVERAGE("--instrument_coverage"),
        KSP_GENERATED_JAVA_SRCJAR("--ksp_generated_java_srcjar"),
        KSP_GENERATED_CLASSES_JAR("--ksp_generated_classes_jar"),
        BUILD_TOOLS_API("--build_tools_api"),
        KSP_OPTS("--ksp_opts"),
      }
    }

    fun build(
      taskContext: WorkerContext.TaskContext,
      args: List<String>,
    ): Int {
      val (argMap, compileContext) = buildContext(taskContext, args)
      var success = false
      var status = 0
      try {
        @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
        when (compileContext.info.platform) {
          Platform.JVM,
          Platform.ANDROID,
          -> executeJvmTask(compileContext, taskContext.directory, argMap, taskContext.sandboxDir)
          Platform.UNRECOGNIZED -> throw IllegalStateException(
            "unrecognized platform: ${compileContext.info}",
          )
        }
        success = true
      } catch (ex: CompilationStatusException) {
        taskContext.error { "Compilation failure: ${ex.message}" }
        status = ex.status
      } catch (throwable: Throwable) {
        taskContext.error(throwable) { "Uncaught exception" }
        status = 1
      } finally {
        compileContext.finalize(success)
      }
      return status
    }

    private fun buildContext(
      ctx: WorkerContext.TaskContext,
      args: List<String>,
    ): Pair<ArgMap, CompilationTaskContext> {
      check(args.isNotEmpty()) { "expected at least a single arg got: ${args.joinToString(" ")}" }
      val lines =
        FLAGFILE_RE.matchEntire(args[0])?.groups?.get(1)?.let {
          Files.readAllLines(
            FileSystems.getDefault().getPath(resolveInSandbox(ctx.sandboxDir, it.value)),
            StandardCharsets.UTF_8,
          )
        } ?: args

      val argMap = ArgMaps.from(lines)
      val info = buildTaskInfo(argMap, ctx.sandboxDir).build()
      val context =
        CompilationTaskContext(info, ctx.asPrintStream())
      return Pair(argMap, context)
    }

    private fun buildTaskInfo(
      argMap: ArgMap,
      sandboxDir: Path?,
    ): CompilationTaskInfo.Builder =
      with(CompilationTaskInfo.newBuilder()) {
        addAllDebug(argMap.mandatory(KotlinBuilderFlags.DEBUG))

        label = argMap.mandatorySingle(KotlinBuilderFlags.TARGET_LABEL)
        argMap.mandatorySingle(KotlinBuilderFlags.RULE_KIND).also {
          val splitRuleKind = it.split("_")
          require(splitRuleKind[0] == "kt") { "Invalid rule kind $it" }
          platform = Platform.valueOf(splitRuleKind[1].uppercase())
          ruleKind = RuleKind.valueOf(splitRuleKind.last().uppercase())
        }
        moduleName =
          argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME).also {
            check(it.isNotBlank()) { "--kotlin_module_name should not be blank" }
          }
        addAllPassthroughFlags(argMap.optional(KotlinBuilderFlags.PASSTHROUGH_FLAGS) ?: emptyList())
        addAllKspOpts(argMap.optional(KotlinBuilderFlags.KSP_OPTS) ?: emptyList())

        argMap
          .optional(KotlinBuilderFlags.FRIEND_PATHS)
          ?.map { resolveInSandbox(sandboxDir, it) }
          ?.let(::addAllFriendPaths)
        toolchainInfoBuilder.commonBuilder.apiVersion =
          argMap.mandatorySingle(KotlinBuilderFlags.API_VERSION)
        toolchainInfoBuilder.commonBuilder.languageVersion =
          argMap.mandatorySingle(KotlinBuilderFlags.LANGUAGE_VERSION)
        strictKotlinDeps = argMap.mandatorySingle(KotlinBuilderFlags.STRICT_KOTLIN_DEPS)
        reducedClasspathMode = argMap.mandatorySingle(KotlinBuilderFlags.REDUCED_CLASSPATH_MODE)
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_INTERNAL_AS_PRIVATE)?.let {
          treatInternalAsPrivateInAbiJar = it == "true"
        }
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_REMOVE_PRIVATE_CLASSES)?.let {
          removePrivateClassesInAbiJar = it == "true"
        }
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_REMOVE_DEBUG_INFO)?.let {
          removeDebugInfo = it == "true"
        }
        argMap.optionalSingle(KotlinBuilderFlags.BUILD_TOOLS_API)?.let {
          buildToolsApi = it == "true"
        }
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_PRESERVE_DECLARATION_ORDER)?.let {
          preserveDeclarationOrder = it == "true"
        }
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_REMOVE_DATA_CLASS_COPY_IF_CONSTRUCTOR_IS_PRIVATE)?.let {
          removeDataClassCopyIfConstructorIsPrivate = it == "true"
        }
        this
      }

    private fun executeJvmTask(
      context: CompilationTaskContext,
      workingDir: Path,
      argMap: ArgMap,
      sandboxDir: Path?,
    ) {
      val task = buildJvmTask(context.info, workingDir, argMap, sandboxDir)
      context.whenTracing {
        printProto("jvm task message:", task)
      }
      jvmTaskExecutor.execute(context, task)
    }

    private fun buildJvmTask(
      info: CompilationTaskInfo,
      workingDir: Path,
      argMap: ArgMap,
      sandboxDir: Path?,
    ): JvmCompilationTask =
      JvmCompilationTask.newBuilder().let { root ->
        root.info = info

        root.compileKotlin = argMap.mandatorySingle(KotlinBuilderFlags.BUILD_KOTLIN).toBoolean()
        root.instrumentCoverage =
          argMap
            .mandatorySingle(
              KotlinBuilderFlags.INSTRUMENT_COVERAGE,
            ).toBoolean()

        // Output files are declared with stripped (config-relative) paths under path mapping; when
        // running in a multiplex sandbox they must be written relative to the sandbox directory.
        with(root.outputsBuilder) {
          argMap.optionalSingle(KotlinBuilderFlags.OUTPUT)?.let { jar = resolveInSandbox(sandboxDir, it) }
          argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_SRCJAR)?.let {
            srcjar = resolveInSandbox(sandboxDir, it)
          }

          argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_JDEPS)?.let {
            jdeps = resolveInSandbox(sandboxDir, it)
          }
          argMap.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_SRC_JAR)?.let {
            generatedJavaSrcJar = resolveInSandbox(sandboxDir, it)
          }
          argMap.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_STUB_JAR)?.let {
            generatedJavaStubJar = resolveInSandbox(sandboxDir, it)
          }
          argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR)?.let { abijar = resolveInSandbox(sandboxDir, it) }
          argMap.optionalSingle(KotlinBuilderFlags.GENERATED_CLASS_JAR)?.let {
            generatedClassJar = resolveInSandbox(sandboxDir, it)
          }
          argMap.optionalSingle(KotlinBuilderFlags.KSP_GENERATED_JAVA_SRCJAR)?.let {
            generatedKspSrcJar = resolveInSandbox(sandboxDir, it)
          }
          argMap.optionalSingle(KotlinBuilderFlags.KSP_GENERATED_CLASSES_JAR)?.let {
            generatedKspClassesJar = resolveInSandbox(sandboxDir, it)
          }
        }

        with(root.directoriesBuilder) {
          val moduleName = argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME)
          classes =
            workingDir.resolveNewDirectories(getOutputDirPath(moduleName, "classes")).toString()
          javaClasses =
            workingDir
              .resolveNewDirectories(
                getOutputDirPath(moduleName, "java_classes"),
              ).toString()
          if (argMap.hasAll(KotlinBuilderFlags.ABI_JAR)) {
            abiClasses =
              workingDir
                .resolveNewDirectories(
                  getOutputDirPath(moduleName, "abi_classes"),
                ).toString()
          }
          generatedClasses =
            workingDir
              .resolveNewDirectories(getOutputDirPath(moduleName, "generated_classes"))
              .toString()
          temp =
            workingDir
              .resolveNewDirectories(
                getOutputDirPath(moduleName, "temp"),
              ).toString()
          generatedSources =
            workingDir
              .resolveNewDirectories(getOutputDirPath(moduleName, "generated_sources"))
              .toString()
          generatedJavaSources =
            workingDir
              .resolveNewDirectories(getOutputDirPath(moduleName, "generated_java_sources"))
              .toString()
          generatedStubClasses =
            workingDir.resolveNewDirectories(getOutputDirPath(moduleName, "stubs")).toString()
          coverageMetadataClasses =
            workingDir
              .resolveNewDirectories(getOutputDirPath(moduleName, "coverage-metadata"))
              .toString()
        }

        // Input files are passed with stripped (config-relative) paths under path mapping; when
        // running in a multiplex sandbox the actual files live under the sandbox directory, so the
        // worker must prefix them before handing them to the compiler. `processors` (class names)
        // are not paths and are left untouched. Plugin option strings (stubs/compiler plugin
        // options, ksp_opts) may embed paths but are not remapped here.
        with(root.inputsBuilder) {
          addAllClasspath(argMap.mandatory(KotlinBuilderFlags.CLASSPATH).map { resolveInSandbox(sandboxDir, it) })
          addAllDepsArtifacts(
            (argMap.optional(KotlinBuilderFlags.DEPS_ARTIFACTS) ?: emptyList()).map {
              resolveInSandbox(sandboxDir, it)
            },
          )
          addAllDirectDependencies(
            argMap.mandatory(KotlinBuilderFlags.DIRECT_DEPENDENCIES).map { resolveInSandbox(sandboxDir, it) },
          )

          addAllProcessors(argMap.optional(KotlinBuilderFlags.PROCESSORS) ?: emptyList())
          addAllProcessorpaths(
            (argMap.optional(KotlinBuilderFlags.PROCESSOR_PATH) ?: emptyList()).map {
              resolveInSandbox(sandboxDir, it)
            },
          )

          addAllStubsPluginOptions(
            argMap.optional(KotlinBuilderFlags.STUBS_PLUGIN_OPTIONS) ?: emptyList(),
          )
          addAllStubsPluginClasspath(
            (argMap.optional(KotlinBuilderFlags.STUBS_PLUGIN_CLASS_PATH) ?: emptyList()).map {
              resolveInSandbox(sandboxDir, it)
            },
          )

          addAllCompilerPluginOptions(
            argMap.optional(KotlinBuilderFlags.COMPILER_PLUGIN_OPTIONS) ?: emptyList(),
          )
          addAllCompilerPluginClasspath(
            (argMap.optional(KotlinBuilderFlags.COMPILER_PLUGIN_CLASS_PATH) ?: emptyList()).map {
              resolveInSandbox(sandboxDir, it)
            },
          )

          argMap
            .optional(KotlinBuilderFlags.SOURCES)
            ?.map { resolveInSandbox(sandboxDir, it) }
            ?.iterator()
            ?.partitionJvmSources(
              { addKotlinSources(it) },
              { addJavaSources(it) },
            )
          argMap
            .optional(KotlinBuilderFlags.SOURCE_JARS)
            ?.map { resolveInSandbox(sandboxDir, it) }
            ?.also {
              addAllSourceJars(it)
            }
        }

        with(root.infoBuilder) {
          toolchainInfoBuilder.jvmBuilder.jvmTarget =
            argMap.mandatorySingle(KotlinBuilderFlags.JVM_TARGET)
        }
        root.build()
      }

    private fun getOutputDirPath(
      moduleName: String,
      dirName: String,
    ) = "_kotlinc/${moduleName}_jvm/$dirName"
  }
