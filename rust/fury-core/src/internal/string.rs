// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

use crate::error::Error;
use crate::resolvers::context::ReadContext;
use crate::resolvers::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{FieldType, FuryGeneralList};
use std::mem;

impl Serializer for String {
    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn write(&self, context: &mut WriteContext) {
        context.writer.var_int32(self.len() as i32);
        context.writer.bytes(self.as_bytes());
    }

    fn read(context: &mut ReadContext) -> Result<Self, Error> {
        let len = context.reader.var_int32();
        Ok(context.reader.string(len as usize))
    }

    fn ty() -> FieldType {
        FieldType::STRING
    }
}

impl FuryGeneralList for String {}
