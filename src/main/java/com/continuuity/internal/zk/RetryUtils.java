/**
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.internal.zk;

import org.apache.zookeeper.KeeperException;

/**
 * Utility class for help determining operation retry condition.
 */
final class RetryUtils {

  /**
   * Tells if a given operation error code can be retried or not.
   * @param code The error code of the operation.
   * @return {@code true} if the operation can be retried.
   */
  public static boolean canRetry(KeeperException.Code code) {
    return (code == KeeperException.Code.CONNECTIONLOSS
          || code == KeeperException.Code.OPERATIONTIMEOUT
          || code == KeeperException.Code.SESSIONEXPIRED
          || code == KeeperException.Code.SESSIONMOVED);
  }

  /**
   * Tells if a given operation exception can be retried or not.
   * @param t The exception raised by an operation.
   * @return {@code true} if the operation can be retried.
   */
  public static boolean canRetry(Throwable t) {
    if (!(t instanceof KeeperException)) {
      return false;
    }
    return canRetry(((KeeperException)t).code());
  }

  private RetryUtils() {
  }
}
