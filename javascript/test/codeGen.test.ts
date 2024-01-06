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

import { describe, expect, test } from '@jest/globals';
import { tupleObjectDescription, tupleObjectType3Description } from './fixtures/tuple';
import { generate } from '../packages/fury/lib/gen/index';
import FuryInternal from '../packages/fury/lib/fury';
import * as beautify from 'js-beautify';

describe('codeGen', () => {
  test('can generate tuple declaration code', () => {
    const fury = FuryInternal({
      refTracking: true, hooks: {
        afterCodeGenerated: (code: string) => {
          return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
        }
      }
    });
    const fn = generate(fury, tupleObjectDescription);
    expect(fn.toString()).toMatchSnapshot();

    const fn2 = generate(fury, tupleObjectType3Description);
    expect(fn2.toString()).toMatchSnapshot();
  })
})
