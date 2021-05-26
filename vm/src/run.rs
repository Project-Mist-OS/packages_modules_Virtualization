// Copyright 2021, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Command to run a VM.

use crate::config::VmConfig;
use crate::sync::AtomicFlag;
use android_system_virtualizationservice::aidl::android::system::virtualizationservice::IVirtualizationService::IVirtualizationService;
use android_system_virtualizationservice::aidl::android::system::virtualizationservice::IVirtualMachine::IVirtualMachine;
use android_system_virtualizationservice::aidl::android::system::virtualizationservice::IVirtualMachineCallback::{
    BnVirtualMachineCallback, IVirtualMachineCallback,
};
use android_system_virtualizationservice::binder::{
    BinderFeatures, DeathRecipient, IBinder, ParcelFileDescriptor, Strong,
};
use android_system_virtualizationservice::binder::{Interface, Result as BinderResult};
use anyhow::{Context, Error};
use std::fs::File;
use std::io;
use std::os::unix::io::{AsRawFd, FromRawFd};
use std::path::Path;

/// Run a VM from the given configuration file.
pub fn command_run(
    virt_manager: Strong<dyn IVirtualizationService>,
    config_path: &Path,
    daemonize: bool,
) -> Result<(), Error> {
    let config_file = File::open(config_path).context("Failed to open config file")?;
    let config =
        VmConfig::load(&config_file).context("Failed to parse config file")?.to_parcelable()?;
    let stdout =
        if daemonize { None } else { Some(ParcelFileDescriptor::new(duplicate_stdout()?)) };
    let vm = virt_manager.startVm(&config, stdout.as_ref()).context("Failed to start VM")?;

    let cid = vm.getCid().context("Failed to get CID")?;
    println!("Started VM from {:?} with CID {}.", config_path, cid);

    if daemonize {
        // Pass the VM reference back to VirtualizationService and have it hold it in the
        // background.
        virt_manager.debugHoldVmRef(&vm).context("Failed to pass VM to VirtualizationService")
    } else {
        // Wait until the VM or VirtualizationService dies. If we just returned immediately then the
        // IVirtualMachine Binder object would be dropped and the VM would be killed.
        wait_for_vm(vm)
    }
}

/// Wait until the given VM or the VirtualizationService itself dies.
fn wait_for_vm(vm: Strong<dyn IVirtualMachine>) -> Result<(), Error> {
    let dead = AtomicFlag::default();
    let callback = BnVirtualMachineCallback::new_binder(
        VirtualMachineCallback { dead: dead.clone() },
        BinderFeatures::default(),
    );
    vm.registerCallback(&callback)?;
    let death_recipient = wait_for_death(&mut vm.as_binder(), dead.clone())?;
    dead.wait();
    // Ensure that death_recipient isn't dropped before we wait on the flag, as it is removed
    // from the Binder when it's dropped.
    drop(death_recipient);
    Ok(())
}

/// Raise the given flag when the given Binder object dies.
///
/// If the returned DeathRecipient is dropped then this will no longer do anything.
fn wait_for_death(binder: &mut impl IBinder, dead: AtomicFlag) -> Result<DeathRecipient, Error> {
    let mut death_recipient = DeathRecipient::new(move || {
        println!("VirtualizationService died");
        dead.raise();
    });
    binder.link_to_death(&mut death_recipient)?;
    Ok(death_recipient)
}

#[derive(Debug)]
struct VirtualMachineCallback {
    dead: AtomicFlag,
}

impl Interface for VirtualMachineCallback {}

impl IVirtualMachineCallback for VirtualMachineCallback {
    fn onDied(&self, _cid: i32) -> BinderResult<()> {
        println!("VM died");
        self.dead.raise();
        Ok(())
    }
}

/// Safely duplicate the standard output file descriptor.
fn duplicate_stdout() -> io::Result<File> {
    let stdout_fd = io::stdout().as_raw_fd();
    // Safe because this just duplicates a file descriptor which we know to be valid, and we check
    // for an error.
    let dup_fd = unsafe { libc::dup(stdout_fd) };
    if dup_fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        // Safe because we have just duplicated the file descriptor so we own it, and `from_raw_fd`
        // takes ownership of it.
        Ok(unsafe { File::from_raw_fd(dup_fd) })
    }
}
