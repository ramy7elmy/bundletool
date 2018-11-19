/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.validation;

import static com.android.tools.build.bundletool.testing.ManifestProtoUtils.androidManifest;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.android.bundle.Files.ApexImages;
import com.android.bundle.Files.TargetedApexImage;
import com.android.tools.build.bundletool.exceptions.ValidationException;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.testing.BundleModuleBuilder;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ApexBundleValidatorTest {
  private static final String PKG_NAME = "com.test.app";
  private static final ApexImages APEX_CONFIG =
      ApexImages.newBuilder()
          .addImage(TargetedApexImage.newBuilder().setPath("apex/x86.img"))
          .build();

  @Test
  public void validateModule_validApexModule_succeeds() throws Exception {
    BundleModule apexModule = validApexModule();

    new ApexBundleValidator().validateModule(apexModule);
  }

  @Test
  public void validateModule_unexpectedFile_throws() throws Exception {
    BundleModule apexModule =
        new BundleModuleBuilder("apexTestModule")
            .setManifest(androidManifest(PKG_NAME))
            .setApexConfig(APEX_CONFIG)
            .addFile("root/manifest.json")
            .addFile("apex/x86.img")
            .addFile("root/unexpected.txt")
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new ApexBundleValidator().validateModule(apexModule));

    assertThat(exception).hasMessageThat().contains("Unexpected file in APEX bundle");
  }

  @Test
  public void validateModule_missingApexManifest_throws() throws Exception {
    BundleModule apexModule =
        new BundleModuleBuilder("apexTestModule")
            .setManifest(androidManifest(PKG_NAME))
            .setApexConfig(APEX_CONFIG)
            .addFile("apex/x86.img")
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new ApexBundleValidator().validateModule(apexModule));

    assertThat(exception).hasMessageThat().contains("Missing expected file in APEX bundle");
  }

  @Test
  public void validateModule_untargetedImageFile_throws() throws Exception {
    BundleModule apexModule =
        new BundleModuleBuilder("apexTestModule")
            .setManifest(androidManifest(PKG_NAME))
            .setApexConfig(APEX_CONFIG)
            .addFile("root/manifest.json")
            .addFile("apex/x86.img")
            .addFile("apex/x86_64.img")
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new ApexBundleValidator().validateModule(apexModule));

    assertThat(exception).hasMessageThat().contains("Found APEX image files that are not targeted");
  }

  @Test
  public void validateModule_missingTargetedImageFile_throws() throws Exception {
    BundleModule apexModule =
        new BundleModuleBuilder("apexTestModule")
            .setManifest(androidManifest(PKG_NAME))
            .setApexConfig(APEX_CONFIG)
            .addFile("root/manifest.json")
            // No image files under apex/.
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new ApexBundleValidator().validateModule(apexModule));

    assertThat(exception).hasMessageThat().contains("Targeted APEX image files are missing");
  }

  @Test
  public void validateAllModules_singleApexModule_succeeds() throws Exception {
    BundleModule apexModule = validApexModule();

    new ApexBundleValidator().validateAllModules(ImmutableList.of(apexModule));
  }

  @Test
  public void validateAllModules_multipleApexModules_throws() throws Exception {
    BundleModule apexModule = validApexModule();

    ValidationException exception =
        assertThrows(
            ValidationException.class,
            () ->
                new ApexBundleValidator()
                    .validateAllModules(ImmutableList.of(apexModule, apexModule)));

    assertThat(exception).hasMessageThat().contains("Multiple APEX modules are not allowed");
  }

  @Test
  public void validateAllModules_ApexModuleWithAnother_throws() throws Exception {
    BundleModule apexModule = validApexModule();
    BundleModule anotherModule =
        new BundleModuleBuilder("anotherModule").setManifest(androidManifest(PKG_NAME)).build();

    ValidationException exception =
        assertThrows(
            ValidationException.class,
            () ->
                new ApexBundleValidator()
                    .validateAllModules(ImmutableList.of(apexModule, anotherModule)));

    assertThat(exception).hasMessageThat().contains("APEX bundles must only contain one module");
  }

  private BundleModule validApexModule() throws IOException {
    return new BundleModuleBuilder("apexTestModule")
        .setManifest(androidManifest(PKG_NAME))
        .setApexConfig(APEX_CONFIG)
        .addFile("root/manifest.json")
        .addFile("apex/x86.img")
        .build();
  }
}
