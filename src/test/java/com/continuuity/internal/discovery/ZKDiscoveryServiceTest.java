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
package com.continuuity.internal.discovery;

import com.continuuity.zookeeper.Cancellable;
import com.continuuity.zookeeper.Discoverable;
import com.continuuity.zookeeper.DiscoveryService;
import com.continuuity.zookeeper.DiscoveryServiceClient;
import com.continuuity.weave.zk.InMemoryZKServer;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import junit.framework.Assert;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test Zookeeper based discovery service.
 */
public class ZKDiscoveryServiceTest {
  private static InMemoryZKServer zkServer;

  @BeforeClass
  public static void beforeClass() {
    zkServer = InMemoryZKServer.builder().setTickTime(100000).build();
    zkServer.startAndWait();
  }

  @AfterClass
  public static void afterClass() {
    zkServer.stopAndWait();
  }

  private Cancellable register(DiscoveryService service, final String name, final String host, final int port) {
    return service.register(new Discoverable() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(host, port);
      }
    });
  }


  private void waitTillExpected(int expected, Iterable<Discoverable> discoverables) throws Exception {
    for(int i = 0; i < 10; ++i) {
      TimeUnit.MILLISECONDS.sleep(10);
      if(Iterables.size(discoverables) == expected) {
        Assert.assertTrue(true);
        return;
      }
    }
    if(Iterables.size(discoverables) == expected) {
      Assert.assertTrue(true);
    } else {
      Assert.assertTrue(false);
    }
  }

  @Test
  public void simpleDiscoverable() throws Exception {
    DiscoveryService discoveryService = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryService.startAndWait();

    DiscoveryServiceClient discoveryServiceClient = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryServiceClient.startAndWait();
    try {
      // Register one service running on one host:port
      Cancellable cancellable = register(discoveryService, "foo", "localhost", 8090);
      Iterable<Discoverable> discoverables = discoveryServiceClient.discover("foo");

      // Discover that registered host:port.
      waitTillExpected(1, discoverables);

      // Remove the service
      cancellable.cancel();

      // There should be no service.

      discoverables = discoveryServiceClient.discover("foo");

      waitTillExpected(0, discoverables);
    } finally {
      discoveryService.stopAndWait();
      discoveryServiceClient.stopAndWait();
    }
  }

  @Test
  public void manySameDiscoverable() throws Exception {
    List<Cancellable> cancellables = Lists.newArrayList();
    DiscoveryService discoveryService = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryService.startAndWait();

    DiscoveryServiceClient discoveryServiceClient
      = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryServiceClient.startAndWait();

    try {
      cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 1));
      cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 2));
      cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 3));
      cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 4));
      cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 5));

      Iterable<Discoverable> discoverables = discoveryServiceClient.discover("manyDiscoverable");
      waitTillExpected(5, discoverables);

      for(int i = 5; i > 1; --i) {
        cancellables.get(5 - i).cancel();
        discoverables = discoveryServiceClient.discover("manyDiscoverable");
        waitTillExpected(i-1, discoverables);
      }
    } finally {
      discoveryService.stopAndWait();
      discoveryServiceClient.stopAndWait();
    }
  }

  @Test
  public void multiServiceDiscoverable() throws Exception {
    List<Cancellable> cancellables = Lists.newArrayList();
    DiscoveryService discoveryService = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryService.startAndWait();
    DiscoveryServiceClient discoveryServiceClient = new ZKDiscoveryService(zkServer.getConnectionStr());
    discoveryServiceClient.startAndWait();

    try {
      cancellables.add(register(discoveryService, "service1", "localhost", 1));
      cancellables.add(register(discoveryService, "service1", "localhost", 2));
      cancellables.add(register(discoveryService, "service1", "localhost", 3));
      cancellables.add(register(discoveryService, "service1", "localhost", 4));
      cancellables.add(register(discoveryService, "service1", "localhost", 5));

      cancellables.add(register(discoveryService, "service2", "localhost", 1));
      cancellables.add(register(discoveryService, "service2", "localhost", 2));
      cancellables.add(register(discoveryService, "service2", "localhost", 3));

      cancellables.add(register(discoveryService, "service3", "localhost", 1));
      cancellables.add(register(discoveryService, "service3", "localhost", 2));

      Iterable<Discoverable> discoverables = discoveryServiceClient.discover("service1");
      waitTillExpected(5, discoverables);

      discoverables = discoveryServiceClient.discover("service2");
      waitTillExpected(3, discoverables);

      discoverables = discoveryServiceClient.discover("service3");
      waitTillExpected(2, discoverables);

      cancellables.add(register(discoveryService, "service3", "localhost", 3));
      waitTillExpected(3, discoverables); // Shows live iterator.
    } finally {
      for(Cancellable cancellable : cancellables) {
        TimeUnit.MILLISECONDS.sleep(100);
        cancellable.cancel();
      }
      discoveryService.stopAndWait();
      discoveryServiceClient.stopAndWait();
    }
  }
}
