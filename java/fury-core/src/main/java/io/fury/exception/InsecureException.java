/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.exception;

/**
 * If class registration is enabled, and class of object which is being serialized is not
 * registered(i.e. not in white-list), then this exception will be thrown.
 *
 * @author chaokunyang
 */
public class InsecureException extends FuryException {
  public InsecureException(String message) {
    super(message);
  }
}
