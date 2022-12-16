// Copyright 2022, The Android Open Source Project
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

//! pVM firmware.

#![no_main]
#![no_std]
#![feature(default_alloc_error_handler)]
#![feature(ptr_const_cast)] // Stabilized in 1.65.0

mod avb;
mod config;
mod entry;
mod exceptions;
mod fdt;
mod heap;
mod helpers;
mod hvc;
mod memory;
mod mmio_guard;
mod mmu;
mod pci;
mod smccc;

use crate::{
    avb::PUBLIC_KEY, // Keep the public key here otherwise the signing script will be broken.
    entry::RebootReason,
    memory::MemoryTracker,
    pci::{find_virtio_devices, map_mmio},
};
use dice::bcc;
use fdtpci::{PciError, PciInfo};
use libfdt::Fdt;
use log::{debug, error, info, trace};

fn main(
    fdt: &Fdt,
    signed_kernel: &[u8],
    ramdisk: Option<&[u8]>,
    bcc: &bcc::Handover,
    memory: &mut MemoryTracker,
) -> Result<(), RebootReason> {
    info!("pVM firmware");
    debug!("FDT: {:?}", fdt as *const libfdt::Fdt);
    debug!("Signed kernel: {:?} ({:#x} bytes)", signed_kernel.as_ptr(), signed_kernel.len());
    debug!("AVB public key: addr={:?}, size={:#x} ({1})", PUBLIC_KEY.as_ptr(), PUBLIC_KEY.len());
    if let Some(rd) = ramdisk {
        debug!("Ramdisk: {:?} ({:#x} bytes)", rd.as_ptr(), rd.len());
    } else {
        debug!("Ramdisk: None");
    }
    trace!("BCC: {bcc:x?}");

    // Set up PCI bus for VirtIO devices.
    let pci_info = PciInfo::from_fdt(fdt).map_err(handle_pci_error)?;
    debug!("PCI: {:#x?}", pci_info);
    map_mmio(&pci_info, memory)?;
    // Safety: This is the only place where we call make_pci_root, and this main function is only
    // called once.
    let mut pci_root = unsafe { pci_info.make_pci_root() };
    find_virtio_devices(&mut pci_root).map_err(handle_pci_error)?;

    info!("Starting payload...");
    Ok(())
}

/// Logs the given PCI error and returns the appropriate `RebootReason`.
fn handle_pci_error(e: PciError) -> RebootReason {
    error!("{}", e);
    match e {
        PciError::FdtErrorPci(_)
        | PciError::FdtNoPci
        | PciError::FdtErrorReg(_)
        | PciError::FdtMissingReg
        | PciError::FdtRegEmpty
        | PciError::FdtRegMissingSize
        | PciError::CamWrongSize(_)
        | PciError::FdtErrorRanges(_)
        | PciError::FdtMissingRanges
        | PciError::RangeAddressMismatch { .. }
        | PciError::NoSuitableRange => RebootReason::InvalidFdt,
    }
}
