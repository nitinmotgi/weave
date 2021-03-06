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
package com.continuuity.weave.internal.yarn;

import com.continuuity.weave.api.LocalFile;
import com.continuuity.weave.api.ResourceSpecification;
import com.continuuity.weave.api.RunId;
import com.continuuity.weave.api.RuntimeSpecification;
import com.continuuity.weave.api.WeaveApplication;
import com.continuuity.weave.api.WeaveController;
import com.continuuity.weave.api.WeavePreparer;
import com.continuuity.weave.api.WeaveRunnable;
import com.continuuity.weave.api.WeaveRunnableSpecification;
import com.continuuity.weave.api.WeaveRunnerService;
import com.continuuity.weave.api.WeaveSpecification;
import com.continuuity.weave.api.logging.LogHandler;
import com.continuuity.weave.internal.api.DefaultLocalFile;
import com.continuuity.weave.internal.api.DefaultWeaveRunnableSpecification;
import com.continuuity.weave.internal.api.DefaultWeaveSpecification;
import com.continuuity.weave.internal.api.RunIds;
import com.continuuity.weave.internal.json.WeaveSpecificationAdapter;
import com.continuuity.weave.internal.logging.KafkaWeaveRunnable;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AbstractIdleService;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.YarnClient;
import org.apache.hadoop.yarn.client.YarnClientImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 */
public final class YarnWeaveRunnerService extends AbstractIdleService implements WeaveRunnerService {

  private static final String KAFKA_ARCHIVE = "kafka-0.7.2.tgz";

  private final YarnClient yarnClient;
  private final String zkConnectStr;

  public YarnWeaveRunnerService(YarnConfiguration config, String zkConnectStr) {
    YarnClient client = new YarnClientImpl();
    client.init(config);

    this.yarnClient = client;
    this.zkConnectStr = zkConnectStr;
  }

  @Override
  public WeavePreparer prepare(WeaveRunnable runnable) {
    return prepare(runnable, ResourceSpecification.BASIC);
  }

  @Override
  public WeavePreparer prepare(WeaveRunnable runnable, ResourceSpecification resourceSpecification) {
    return prepare(new SingleRunnableApplication(runnable, resourceSpecification));
  }

  @Override
  public WeavePreparer prepare(WeaveApplication application) {
    final WeaveSpecification weaveSpec = addKafka(application.configure());
    final List<Closeable> resourceCleaner = Lists.newArrayList();
    final Queue<LogHandler> logHandlers = new ConcurrentLinkedQueue<LogHandler>();

    return new WeavePreparer() {
      @Override
      public WeavePreparer addLogHandler(LogHandler handler) {
        logHandlers.add(handler);
        return this;
      }

      @Override
      public WeavePreparer addErrorHandler() {
        return this;
      }

      @Override
      public WeaveController start() {
        // TODO: Unify this with {@link ProcessLauncher}
        try {
          GetNewApplicationResponse response = yarnClient.getNewApplication();

          ApplicationSubmissionContext appSubmissionContext = Records.newRecord(ApplicationSubmissionContext.class);
          appSubmissionContext.setApplicationId(response.getApplicationId());
          appSubmissionContext.setApplicationName(weaveSpec.getName());

          Map<String, LocalResource> localResources = Maps.newHashMap();
          LocalResource weaveSpecResource = Records.newRecord(LocalResource.class);
          resourceCleaner.add(saveWeaveSpec(weaveSpec, weaveSpecResource));
          localResources.put("weaveSpec.json", weaveSpecResource);

          resourceCleaner.add(getLocalResources(weaveSpec, localResources));

          ContainerLaunchContext containerLaunchContext = Records.newRecord(ContainerLaunchContext.class);
          containerLaunchContext.setLocalResources(localResources);

          RunId runId = RunIds.generate();
          containerLaunchContext.setCommands(
            ImmutableList.of("java",
                             ApplicationMasterMain.class.getName(),
                             zkConnectStr,
                             "weaveSpec.json",
                             runId.getId()));

          // TODO: Should figure out jar dependencies
          containerLaunchContext.setEnvironment(ImmutableMap.of("CLASSPATH", System.getProperty("java.class.path")));
          Resource capability = Records.newRecord(Resource.class);
          capability.setMemory(256);
          containerLaunchContext.setResource(capability);

          appSubmissionContext.setAMContainerSpec(containerLaunchContext);

          yarnClient.submitApplication(appSubmissionContext);

          return createController(runId, logHandlers);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  private WeaveController createController(RunId runId, Collection<LogHandler> logHandlers) {
    ZKWeaveController controller = new ZKWeaveController(zkConnectStr, 10000, runId, logHandlers);
    controller.start();
    return controller;
  }

  @Override
  public WeaveController lookup(RunId runId) {
    // TODO: Check if the runId presences in ZK.
    return new ZKWeaveController(zkConnectStr, 10000, runId, ImmutableList.<LogHandler>of());
  }

  @Override
  protected void startUp() throws Exception {
    yarnClient.start();
  }

  @Override
  protected void shutDown() throws Exception {
    yarnClient.stop();
  }

  // Add the kafka runnable.
  // TODO: It is a bit hacky to just add it in here
  private WeaveSpecification addKafka(final WeaveSpecification weaveSpec) {
    final String kafkaName = "kafka";

    return new WeaveSpecification() {
      @Override
      public String getName() {
        return weaveSpec.getName();
      }

      @Override
      public Map<String, RuntimeSpecification> getRunnables() {
        RuntimeSpecification kafkaRuntimeSpec = new RuntimeSpecification() {

          @Override
          public String getName() {
            return kafkaName;
          }

          @Override
          public WeaveRunnableSpecification getRunnableSpecification() {
            KafkaWeaveRunnable kafkaRunnable = new KafkaWeaveRunnable("kafka.tgz");
            return new DefaultWeaveRunnableSpecification(kafkaRunnable.getClass().getName(),
                                                         kafkaRunnable.configure());
          }

          @Override
          public ResourceSpecification getResourceSpecification() {
            return ResourceSpecification.Builder.with()
              .setCores(1).setMemory(1, ResourceSpecification.SizeUnit.GIGA).build();
          }

          @Override
          public Collection<LocalFile> getLocalFiles() {
            try {
              URL kafkaArchive = getClass().getClassLoader().getResource(KAFKA_ARCHIVE);
              LocalFile kafka = new DefaultLocalFile("kafka.tgz", kafkaArchive.toURI(), true, null);
              return ImmutableList.of(kafka);
            } catch (Exception e) {
              throw Throwables.propagate(e);
            }
          }
        };

        return ImmutableMap.<String, RuntimeSpecification>builder()
                      .putAll(weaveSpec.getRunnables())
                      .put(kafkaName, kafkaRuntimeSpec)
                      .build();
      }

      @Override
      public List<Order> getOrders() {
        ImmutableList.Builder<Order> orders = ImmutableList.builder();
        orders.add(new DefaultWeaveSpecification.DefaultOrder(ImmutableSet.of(kafkaName), Order.Type.STARTED));
        orders.addAll(weaveSpec.getOrders());
        return orders.build();
      }
    };
  }

  private Closeable saveWeaveSpec(WeaveSpecification spec, LocalResource resource) throws IOException{
    final File file = File.createTempFile("weaveSpec", ".json");
    WeaveSpecificationAdapter.create().toJson(spec, file);

    resource.setResource(ConverterUtils.getYarnUrlFromURI(file.toURI()));
    resource.setSize(file.length());
    resource.setTimestamp(file.lastModified());
    resource.setType(LocalResourceType.FILE);
    resource.setVisibility(LocalResourceVisibility.APPLICATION);

    return new Closeable() {
      @Override
      public void close() throws IOException {
        file.delete();
      }
    };
  }

  private Closeable getLocalResources(WeaveSpecification weaveSpec,
                                      Map<String, LocalResource> localResources) throws IOException {
    final List<File> tmpFiles = Lists.newArrayList();

    for (Map.Entry<String, RuntimeSpecification> entry: weaveSpec.getRunnables().entrySet()) {
      String name = entry.getKey();
      for (LocalFile localFile : entry.getValue().getLocalFiles()) {
        LocalResource localResource = Records.newRecord(LocalResource.class);
        localResource.setVisibility(LocalResourceVisibility.APPLICATION);
        localResource.setType(LocalResourceType.FILE);

        File tmpFile = copyFromURI(localFile.getURI(), File.createTempFile(localFile.getName(), ".tmp"));
        tmpFiles.add(tmpFile);

        localResource.setResource(ConverterUtils.getYarnUrlFromURI(tmpFile.toURI()));
        localResource.setSize(tmpFile.length());
        localResource.setTimestamp(tmpFile.lastModified());

        localResources.put(name + "." + localFile.getName(), localResource);
      }
    }

    return new Closeable() {
      @Override
      public void close() throws IOException {
        for (File file : tmpFiles) {
          file.delete();
        }
      }
    };
  }

  private File copyFromURI(URI uri, File target) throws IOException {
    InputStream is = uri.toURL().openStream();
    try {
      ByteStreams.copy(is, Files.newOutputStreamSupplier(target));
      if ("file".equals(uri.getScheme())) {
        target.setLastModified(new File(uri).lastModified());
      }
      return target;
    } finally {
      is.close();
    }
  }
}
