// Copyright 2023, The Android Open Source Project
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

//! Safe wrappers around the BoringSSL API.

#![cfg_attr(not(feature = "std"), no_std)]

extern crate alloc;

mod cbb;
mod digest;
mod ec_key;
mod hmac;

pub use bssl_avf_error::{ApiName, Error, Result};
pub use cbb::CbbFixed;
pub use ec_key::{EcKey, ZVec};
pub use hmac::hmac_sha256;
