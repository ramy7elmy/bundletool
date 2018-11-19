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

package com.android.tools.build.bundletool.commands;

import static com.android.tools.build.bundletool.utils.files.FilePreconditions.checkDirectoryExists;
import static com.android.tools.build.bundletool.utils.files.FilePreconditions.checkFileExistsAndExecutable;
import static com.android.tools.build.bundletool.utils.files.FilePreconditions.checkFileExistsAndReadable;

import com.android.bundle.Devices.DeviceSpec;
import com.android.tools.build.bundletool.commands.CommandHelp.CommandDescription;
import com.android.tools.build.bundletool.commands.CommandHelp.FlagDescription;
import com.android.tools.build.bundletool.device.AdbServer;
import com.android.tools.build.bundletool.device.ApksInstaller;
import com.android.tools.build.bundletool.device.Device.InstallOptions;
import com.android.tools.build.bundletool.device.DeviceAnalyzer;
import com.android.tools.build.bundletool.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.io.TempFiles;
import com.android.tools.build.bundletool.utils.EnvironmentVariableProvider;
import com.android.tools.build.bundletool.utils.SdkToolsLocator;
import com.android.tools.build.bundletool.utils.SystemEnvironmentVariableProvider;
import com.android.tools.build.bundletool.utils.flags.Flag;
import com.android.tools.build.bundletool.utils.flags.ParsedFlags;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Installs APKs on a connected device. */
@AutoValue
public abstract class InstallApksCommand {

  public static final String COMMAND_NAME = "install-apks";

  private static final Flag<Path> ADB_PATH_FLAG = Flag.path("adb");
  private static final Flag<Path> APKS_ARCHIVE_FILE_FLAG = Flag.path("apks");
  private static final Flag<String> DEVICE_ID_FLAG = Flag.string("device-id");
  private static final Flag<ImmutableSet<String>> MODULES_FLAG = Flag.stringSet("modules");
  private static final Flag<Boolean> ALLOW_DOWNGRADE_FLAG = Flag.booleanFlag("allow-downgrade");

  private static final String ANDROID_HOME_VARIABLE = "ANDROID_HOME";
  private static final String ANDROID_SERIAL_VARIABLE = "ANDROID_SERIAL";

  private static final EnvironmentVariableProvider DEFAULT_PROVIDER =
      new SystemEnvironmentVariableProvider();

  public abstract Path getAdbPath();

  public abstract Path getApksArchivePath();

  public abstract Optional<String> getDeviceId();

  public abstract Optional<ImmutableSet<String>> getModules();

  public abstract boolean getAllowDowngrade();

  abstract AdbServer getAdbServer();

  public static Builder builder() {
    return new AutoValue_InstallApksCommand.Builder().setAllowDowngrade(false);
  }

  /** Builder for the {@link InstallApksCommand}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setAdbPath(Path adbPath);

    public abstract Builder setApksArchivePath(Path apksArchivePath);

    public abstract Builder setDeviceId(String deviceId);

    public abstract Builder setModules(ImmutableSet<String> modules);

    public abstract Builder setAllowDowngrade(boolean allowDowngrade);

    /** The caller is responsible for the lifecycle of the {@link AdbServer}. */
    public abstract Builder setAdbServer(AdbServer adbServer);

    public abstract InstallApksCommand build();
  }

  public static InstallApksCommand fromFlags(ParsedFlags flags, AdbServer adbServer) {
    return fromFlags(flags, DEFAULT_PROVIDER, adbServer);
  }

  public static InstallApksCommand fromFlags(
      ParsedFlags flags,
      EnvironmentVariableProvider environmentVariableProvider,
      AdbServer adbServer) {
    Path apksArchivePath = APKS_ARCHIVE_FILE_FLAG.getRequiredValue(flags);
    Path adbPath =
        ADB_PATH_FLAG
            .getValue(flags)
            .orElseGet(
                () ->
                    environmentVariableProvider
                        .getVariable(ANDROID_HOME_VARIABLE)
                        .flatMap(path -> new SdkToolsLocator().locateAdb(Paths.get(path)))
                        .orElseThrow(
                            () ->
                                new CommandExecutionException(
                                    "Unable to determine the location of ADB. Please set the --adb "
                                        + "flag or define ANDROID_HOME environment variable.")));

    Optional<String> deviceSerialName = DEVICE_ID_FLAG.getValue(flags);
    if (!deviceSerialName.isPresent()) {
      deviceSerialName = environmentVariableProvider.getVariable(ANDROID_SERIAL_VARIABLE);
    }

    Optional<ImmutableSet<String>> modules = MODULES_FLAG.getValue(flags);
    Optional<Boolean> allowDowngrade = ALLOW_DOWNGRADE_FLAG.getValue(flags);

    flags.checkNoUnknownFlags();

    InstallApksCommand.Builder command =
        builder().setAdbPath(adbPath).setAdbServer(adbServer).setApksArchivePath(apksArchivePath);
    deviceSerialName.ifPresent(command::setDeviceId);
    modules.ifPresent(command::setModules);
    allowDowngrade.ifPresent(command::setAllowDowngrade);

    return command.build();
  }

  public void execute() {
    validateInput();

    AdbServer adbServer = getAdbServer();
    adbServer.init(getAdbPath());

    TempFiles.withTempDirectory(
        tempDir -> {
          DeviceSpec deviceSpec = new DeviceAnalyzer(adbServer).getDeviceSpec(getDeviceId());

          ExtractApksCommand.Builder extractApksCommand =
              ExtractApksCommand.builder()
                  .setApksArchivePath(getApksArchivePath())
                  .setDeviceSpec(deviceSpec);

          if (!Files.isDirectory(getApksArchivePath())) {
            extractApksCommand.setOutputDirectory(tempDir);
          }
          getModules().ifPresent(extractApksCommand::setModules);
          ImmutableList<Path> extractedApks = extractApksCommand.build().execute();

          ApksInstaller installer = new ApksInstaller(adbServer);
          InstallOptions installOptions =
              InstallOptions.builder().setAllowDowngrade(getAllowDowngrade()).build();

          if (getDeviceId().isPresent()) {
            installer.installApks(extractedApks, installOptions, getDeviceId().get());
          } else {
            installer.installApks(extractedApks, installOptions);
          }
        });
  }

  private void validateInput() {
    if (Files.isDirectory(getApksArchivePath())) {
      checkDirectoryExists(getApksArchivePath());
    } else {
      checkFileExistsAndReadable(getApksArchivePath());
    }
    checkFileExistsAndExecutable(getAdbPath());
  }

  public static CommandHelp help() {
    return CommandHelp.builder()
        .setCommandName(COMMAND_NAME)
        .setCommandDescription(
            CommandDescription.builder()
                .setShortDescription(
                    "Installs APKs extracted from an APK Set to a connected device. "
                        + "Replaces already installed package.")
                .addAdditionalParagraph(
                    "This will extract from the APK Set archive and install only the APKs that "
                        + "would be served to that device. If the app is not compatible with the "
                        + "device or if the APK Set archive was generated for a different type of "
                        + "device, this command will fail.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(ADB_PATH_FLAG.getName())
                .setExampleValue("path/to/adb")
                .setOptional(true)
                .setDescription(
                    "Path to the adb utility. If absent, an attempt will be made to locate it if "
                        + "the %s environment variable is set.",
                    ANDROID_HOME_VARIABLE)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(APKS_ARCHIVE_FILE_FLAG.getName())
                .setExampleValue("archive.apks")
                .setDescription(
                    "Path to the archive file generated by the '%s' command.",
                    BuildApksCommand.COMMAND_NAME)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(DEVICE_ID_FLAG.getName())
                .setExampleValue("device-serial-name")
                .setOptional(true)
                .setDescription(
                    "Device serial name. If absent, this uses the %s environment variable. Either "
                        + "this flag or the environment variable is required when more than one "
                        + "device or emulator is connected.",
                    ANDROID_SERIAL_VARIABLE)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(DEVICE_ID_FLAG.getName())
                .setExampleValue("allow-downgrade")
                .setOptional(true)
                .setDescription(
                    "If set, allows APKs to be installed on the device even if the app is already "
                        + "installed with a lower version code.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(MODULES_FLAG.getName())
                .setExampleValue("base,module1,module2")
                .setOptional(true)
                .setDescription(
                    "List of modules to be installed (defaults to all of them). Note that the "
                        + "dependent modules will also be installed. Ignored if the device "
                        + "receives a standalone APK.")
                .build())
        .build();
  }
}
