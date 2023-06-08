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

//! Exception handlers.

use crate::memory::MEMORY;
use core::fmt;
use vmbase::console;
use vmbase::logger;
use vmbase::memory::{page_4kb_of, MemoryTrackerError};
use vmbase::read_sysreg;
use vmbase::{eprintln, power::reboot};

const UART_PAGE: usize = page_4kb_of(console::BASE_ADDRESS);

#[derive(Debug)]
enum HandleExceptionError {
    PageTableUnavailable,
    PageTableNotInitialized,
    InternalError(MemoryTrackerError),
    UnknownException,
}

impl From<MemoryTrackerError> for HandleExceptionError {
    fn from(other: MemoryTrackerError) -> Self {
        Self::InternalError(other)
    }
}

impl fmt::Display for HandleExceptionError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::PageTableUnavailable => write!(f, "Page table is not available."),
            Self::PageTableNotInitialized => write!(f, "Page table is not initialized."),
            Self::InternalError(e) => write!(f, "Error while updating page table: {e}"),
            Self::UnknownException => write!(f, "An unknown exception occurred, not handled."),
        }
    }
}

#[derive(Debug, PartialEq, Copy, Clone)]
enum Esr {
    DataAbortTranslationFault,
    DataAbortPermissionFault,
    DataAbortSyncExternalAbort,
    Unknown(usize),
}

impl Esr {
    const EXT_DABT_32BIT: usize = 0x96000010;
    const TRANSL_FAULT_BASE_32BIT: usize = 0x96000004;
    const TRANSL_FAULT_ISS_MASK_32BIT: usize = !0x143;
    const PERM_FAULT_BASE_32BIT: usize = 0x9600004C;
    const PERM_FAULT_ISS_MASK_32BIT: usize = !0x103;
}

impl From<usize> for Esr {
    fn from(esr: usize) -> Self {
        if esr == Self::EXT_DABT_32BIT {
            Self::DataAbortSyncExternalAbort
        } else if esr & Self::TRANSL_FAULT_ISS_MASK_32BIT == Self::TRANSL_FAULT_BASE_32BIT {
            Self::DataAbortTranslationFault
        } else if esr & Self::PERM_FAULT_ISS_MASK_32BIT == Self::PERM_FAULT_BASE_32BIT {
            Self::DataAbortPermissionFault
        } else {
            Self::Unknown(esr)
        }
    }
}

impl fmt::Display for Esr {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::DataAbortSyncExternalAbort => write!(f, "Synchronous external abort"),
            Self::DataAbortTranslationFault => write!(f, "Translation fault"),
            Self::DataAbortPermissionFault => write!(f, "Permission fault"),
            Self::Unknown(v) => write!(f, "Unknown exception esr={v:#08x}"),
        }
    }
}

#[inline]
fn handle_translation_fault(far: usize) -> Result<(), HandleExceptionError> {
    let mut guard = MEMORY.try_lock().ok_or(HandleExceptionError::PageTableUnavailable)?;
    let memory = guard.as_mut().ok_or(HandleExceptionError::PageTableNotInitialized)?;
    Ok(memory.handle_mmio_fault(far)?)
}

#[inline]
fn handle_permission_fault(far: usize) -> Result<(), HandleExceptionError> {
    let mut guard = MEMORY.try_lock().ok_or(HandleExceptionError::PageTableUnavailable)?;
    let memory = guard.as_mut().ok_or(HandleExceptionError::PageTableNotInitialized)?;
    Ok(memory.handle_permission_fault(far)?)
}

fn handle_exception(esr: Esr, far: usize) -> Result<(), HandleExceptionError> {
    // Handle all translation faults on both read and write, and MMIO guard map
    // flagged invalid pages or blocks that caused the exception.
    // Handle permission faults for DBM flagged entries, and flag them as dirty on write.
    match esr {
        Esr::DataAbortTranslationFault => handle_translation_fault(far),
        Esr::DataAbortPermissionFault => handle_permission_fault(far),
        _ => Err(HandleExceptionError::UnknownException),
    }
}

#[inline]
fn handling_uart_exception(esr: Esr, far: usize) -> bool {
    esr == Esr::DataAbortSyncExternalAbort && page_4kb_of(far) == UART_PAGE
}

#[no_mangle]
extern "C" fn sync_exception_current(elr: u64, _spsr: u64) {
    // Disable logging in exception handler to prevent unsafe writes to UART.
    let _guard = logger::suppress();
    let esr: Esr = read_sysreg!("esr_el1").into();
    let far = read_sysreg!("far_el1");

    if let Err(e) = handle_exception(esr, far) {
        // Don't print to the UART if we are handling an exception it could raise.
        if !handling_uart_exception(esr, far) {
            eprintln!("sync_exception_current");
            eprintln!("{e}");
            eprintln!("{esr}, far={far:#08x}, elr={elr:#08x}");
        }
        reboot()
    }
}

#[no_mangle]
extern "C" fn irq_current(_elr: u64, _spsr: u64) {
    eprintln!("irq_current");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_current(_elr: u64, _spsr: u64) {
    eprintln!("fiq_current");
    reboot();
}

#[no_mangle]
extern "C" fn serr_current(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("serr_current");
    eprintln!("esr={esr:#08x}");
    reboot();
}

#[no_mangle]
extern "C" fn sync_lower(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("sync_lower");
    eprintln!("esr={esr:#08x}");
    reboot();
}

#[no_mangle]
extern "C" fn irq_lower(_elr: u64, _spsr: u64) {
    eprintln!("irq_lower");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_lower(_elr: u64, _spsr: u64) {
    eprintln!("fiq_lower");
    reboot();
}

#[no_mangle]
extern "C" fn serr_lower(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("serr_lower");
    eprintln!("esr={esr:#08x}");
    reboot();
}
