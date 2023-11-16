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
    "//kotlin/internal:defs.bzl",
    _KtJvmInfo = "KtJvmInfo",
    _TOOLCHAIN_TYPE = "TOOLCHAIN_TYPE",
)
load(
    "//kotlin/internal/jvm:kt_android_local_test_impl.bzl",
    _kt_android_local_test_impl = "kt_android_local_test_impl",
)
load(
    "//kotlin/internal/jvm:jvm.bzl",
    _lib_common_attr_exposed = "lib_common_attr_exposed",
    _runnable_common_attr_exposed = "runnable_common_attr_exposed",
)
load(
    "//kotlin/internal/utils:utils.bzl",
    _utils = "utils",
)
load(
    "@build_bazel_rules_android//rules/android_local_test:rule.bzl",
    _make_rule = "make_rule",
)
load(
    "@build_bazel_rules_android//rules/android_local_test:attrs.bzl",
    _BASE_ATTRS = "ATTRS",
)

_ATTRS = _utils.add_dicts(_BASE_ATTRS, _runnable_common_attr_exposed, {
    "main_class": attr.string(default = "com.google.testing.junit.runner.BazelTestRunner"),
    "env": attr.string_dict(
        doc = "Specifies additional environment variables to set when the target is executed by bazel test.",
        default = {},
    ),
    "_lcov_merger": attr.label(
        default = Label("@bazel_tools//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:Main"),
    ),
})

kt_android_local_test = _make_rule(
    implementation = _kt_android_local_test_impl,
    attrs = _ATTRS,
    additional_toolchains = [_TOOLCHAIN_TYPE],
)
