/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import Fury, { TypeDescription, InternalSerializerType } from '../packages/fury/index';
import { describe, expect, test } from '@jest/globals';

describe('fury', () => {
    test('should deserialize null work', () => {
        const fury = new Fury();
        expect(fury.deserialize(new Uint8Array([1]))).toBe(null)
    });

    test('should deserialize big endian work', () => {
        const fury = new Fury();
        try {
            fury.deserialize(new Uint8Array([0]))
            throw new Error('unreachable code')
        } catch (error) {
            expect(error.message).toBe('big endian is not supported now');
        }
    });

    test('should deserialize xlang disable work', () => {
        const fury = new Fury();
        try {
            fury.deserialize(new Uint8Array([2]))
            throw new Error('unreachable code')
        } catch (error) {
            expect(error.message).toBe('support crosslanguage mode only');
        }
    });

    test('should deserialize xlang disable work', () => {
        const fury = new Fury();
        try {
            fury.deserialize(new Uint8Array([14]))
            throw new Error('unreachable code')
        } catch (error) {
            expect(error.message).toBe('outofband mode is not supported now');
        }
    });
});
