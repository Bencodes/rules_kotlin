# Copyright 2018 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
load(
    "//kotlin/internal/jvm:compile.bzl",
    "export_only_providers",
    _compile = "compile",
    _kt_jvm_produce_output_jar_actions = "kt_jvm_produce_output_jar_actions",
)
load(
    "@build_bazel_rules_android//rules:java.bzl",
    _java = "java",
)
load(
    "@build_bazel_rules_android//rules:processing_pipeline.bzl",
    _ProviderInfo = "ProviderInfo",
    _processing_pipeline = "processing_pipeline",
)
load(
    "@build_bazel_rules_android//rules/android_local_test:impl.bzl",
    _BASE_PROCESSORS = "PROCESSORS",
    _finalize = "finalize",
)
load(
    "@build_bazel_rules_android//rules:utils.bzl",
    _get_android_sdk = "get_android_sdk",
    _get_android_toolchain = "get_android_toolchain",
    _utils = "utils",
)

JACOCOCO_CLASS = "com.google.testing.coverage.JacocoCoverageRunner"

def _process_jvm(ctx, resources_ctx, **unused_sub_ctxs):
    """Custom JvmProcessor that handles Kotlin compilation
    """
    outputs = struct(jar = ctx.outputs.jar, srcjar = ctx.actions.declare_file(ctx.label.name + "-src.jar"))

    deps = getattr(ctx.attr, "deps", [])
    associates = getattr(ctx.attr, "associates", [])
    runtime_deps = getattr(ctx.attr, "runtime_deps", [])
    _compile.verify_associates_not_duplicated_in_deps(deps = deps, associates = associates)

    android_dep_infos = [_get_android_sdk_jar(ctx)]
    android_dep_infos.append(_compile.java_info(_get_android_toolchain(ctx).testsupport))
    if resources_ctx.r_java:
        android_dep_infos.append(resources_ctx.r_java)

    #    android_dep_infos.extend(_get_android_resource_class_jars(deps + associates))
    android_dep_infos.extend([_compile.java_info(d) for d in deps])

    compile_deps = _compile.jvm_deps(
        ctx,
        toolchains = _compile.compiler_toolchains(ctx),
        deps = android_dep_infos,
        associates = [_compile.java_info(d) for d in associates],
        runtime_deps = [_compile.java_info(d) for d in runtime_deps],
    )

    # Setup the compile action.
    providers = _kt_jvm_produce_output_jar_actions(
        ctx,
        rule_kind = "kt_jvm_test",
        compile_deps = compile_deps,
        outputs = outputs,
    )
    java_info = java_common.add_constraints(providers.java, "android")

    if ctx.configuration.coverage_enabled:
        deps.append(_get_android_toolchain(ctx).jacocorunner)
        java_start_class = JACOCOCO_CLASS
        coverage_start_class = ctx.attr.main_class
    else:
        java_start_class = ctx.attr.main_class
        coverage_start_class = None

    # Create test run action
    runfiles = depset(
        [resources_ctx.class_jar] + [_get_android_sdk(ctx).android_jar],
        transitive = [providers.java.transitive_runtime_jars],
    ).to_list()

    return _ProviderInfo(
        name = "jvm_ctx",
        value = struct(
            java_info = java_info,
            providers = [
                providers.kt,
                java_info,
            ],
            deps = (
                ctx.attr._implicit_classpath +
                deps +
                associates +
                [_get_android_toolchain(ctx).testsupport]
            ),
            java_start_class = java_start_class,
            coverage_start_class = coverage_start_class,
            android_properties_file = ctx.attr.robolectric_properties_file,
            additional_jvm_flags = [],
        ),
        runfiles = ctx.runfiles(files = runfiles),
    )

PROCESSORS = _processing_pipeline.replace(
    _BASE_PROCESSORS,
    JvmProcessor = _process_jvm,
)

_PROCESSING_PIPELINE = _processing_pipeline.make_processing_pipeline(
    processors = PROCESSORS,
    finalize = _finalize,
)

def kt_android_local_test_impl(ctx):
    """The rule implementation.

    Args:
      ctx: The context.

    Returns:
      A list of providers.
    """
    java_package = _java.resolve_package_from_label(ctx.label, ctx.attr.custom_package)
    return _processing_pipeline.run(ctx, java_package, _PROCESSING_PIPELINE)

def _get_android_sdk_jar(ctx):
    android_jar = _get_android_sdk(ctx).android_jar
    return JavaInfo(output_jar = android_jar, compile_jar = android_jar, neverlink = True)

def _get_android_resource_class_jars(targets):
    """Encapsulates compiler dependency metadata."""

    android_compile_dependencies = []

    # Collect R.class jar files from direct dependencies
    for d in targets:
        if AndroidLibraryResourceClassJarProvider in d:
            jars = d[AndroidLibraryResourceClassJarProvider].jars
            if jars:
                android_compile_dependencies.extend([
                    JavaInfo(output_jar = jar, compile_jar = jar, neverlink = True)
                    for jar in _utils.list_or_depset_to_list(jars)
                ])

    return android_compile_dependencies
