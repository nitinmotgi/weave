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
package com.continuuity.weave.internal.api;

import com.continuuity.weave.api.RunId;
import com.google.common.primitives.Ints;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public final class RunIds {

  private static final AtomicLong lastTimestamp = new AtomicLong();

  public static RunId generate() {
//    return new RunIdImpl(generateUUIDFromCurrentTime().toString());
    return new RunIdImpl(UUID.randomUUID().toString());
  }

  public static RunId fromString(String str) {
    return new RunIdImpl(UUID.fromString(str).toString());
  }

  private static UUID generateUUIDFromCurrentTime() {
    // Number of 100ns since 15 October 1582 00:00:000000000
    final long NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0x01b21dd213814000L;

    // Use a unique timestamp
    long lastTs;
    long ts;
    do {
      lastTs = lastTimestamp.get();
      ts = System.currentTimeMillis();
      if (ts == lastTs) {
        ts++;
      }
    } while (!lastTimestamp.compareAndSet(lastTs, ts));

    long time = ts * 10000 + NUM_100NS_INTERVALS_SINCE_UUID_EPOCH;
    long timeLow = time & 0xffffffffL;
    long timeMid = time & 0xffff00000000L;
    long timeHi = time & 0xfff000000000000L;
    long upperLong = (timeLow << 32) | (timeMid >> 16) | (1 << 12) | (timeHi >> 48) ;

    // Random clock ID
    Random random = new Random();
    int clockId = random.nextInt() & 0x3FFF;
    long nodeId;

    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      NetworkInterface networkInterface = null;
      while (interfaces.hasMoreElements()) {
        networkInterface = interfaces.nextElement();
        if (!networkInterface.isLoopback()) {
          break;
        }
      }
      byte[] mac = networkInterface == null ? null : networkInterface.getHardwareAddress();
      if (mac == null) {
        nodeId = (random.nextLong() & 0xFFFFFFL) | 0x100000L;
      } else {
        nodeId = ((long) Ints.fromBytes(mac[0], mac[1], mac[2], mac[3]) << 16)
          | Ints.fromBytes((byte)0, (byte)0, mac[4], mac[5]);
      }

    } catch (SocketException e) {
      // Generate random node ID
      nodeId = random.nextLong() & 0xFFFFFFL | 0x100000L;
    }

    long lowerLong = ((long)clockId | 0x8000) << 48 | nodeId;

    return new java.util.UUID(upperLong, lowerLong);
  }

  private RunIds() {
  }

  private static final class RunIdImpl implements RunId {

    final String id;

    private RunIdImpl(String id) {
      this.id = id;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String toString() {
      return getId();
    }
  }
}
