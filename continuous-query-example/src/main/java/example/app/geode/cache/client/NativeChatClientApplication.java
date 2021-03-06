/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package example.app.geode.cache.client;

import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.apache.geode.cache.RegionService;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.QueryService;
import org.cp.elements.lang.SystemUtils;

import example.app.chat.model.Chat;

/**
 * The {@link NativeChatClientApplication} class is an {@link AbstractChatClientApplication} implemented by using
 * Apache Geode's public, native Java API.
 *
 * @author John Blum
 * @see java.lang.Runnable
 * @see org.apache.geode.cache.client.ClientCache
 * @see example.app.geode.cache.client.AbstractChatClientApplication
 * @since 1.0.0
 */
public class NativeChatClientApplication extends AbstractChatClientApplication implements Runnable {

  protected static final boolean DURABLE = true;

  protected static final String CACHE_SERVER_HOST =
    System.getProperty("example.continuous-query.gemfire.cache.server.host", "localhost");

  protected static final String CHAT_REGION_NAME = "Chat";
  protected static final String GEMFIRE_LOG_LEVEL = "config";

  protected static final int CACHE_SERVER_PORT =
    Integer.parseInt(System.getProperty("example.continuous-query.gemfire.cache.server.port", "40404"));

  protected static final String CONTINUOUS_QUERY = "SELECT * FROM /Chats";

  protected static final String POOL_NAME = "DEFAULT";

  public static void main(String[] args) {
    new NativeChatClientApplication(args).run();
  }

  private final String[] arguments;

  NativeChatClientApplication(String[] args) {
    this.arguments = Optional.ofNullable(args).orElseGet(() -> new String[0]);
  }

  protected String[] getArguments() {
    return this.arguments;
  }

  @Override
  public void run() {
    run(getArguments());
  }

  @SuppressWarnings("unused")
  protected void run(String[] args) {

    try {
      postProcess(registerContinuousQuery(chatRegion(registerShutdownHook(
        gemfireCache(gemfireProperties()), DURABLE))));

      SystemUtils.promptPressEnterToExit();
    }
    catch (Exception cause) {
      throw new RuntimeException("Failed to start GemFire native cache client application", cause);
    }
  }

  Properties gemfireProperties() {

    Properties gemfireProperties = new Properties();

    gemfireProperties.setProperty("name", NativeChatClientApplication.class.getSimpleName());
    gemfireProperties.setProperty("log-level", GEMFIRE_LOG_LEVEL);
    gemfireProperties.setProperty("durable-client-id", UUID.randomUUID().toString());

    return gemfireProperties;
  }

  ClientCache gemfireCache(Properties gemfireProperties) {

    return new ClientCacheFactory(gemfireProperties)
      .addPoolServer(CACHE_SERVER_HOST, CACHE_SERVER_PORT)
      .setPoolSubscriptionEnabled(true)
      .create();
  }

  ClientCache registerShutdownHook(ClientCache clientCache, boolean keepAlive) {

    Runtime.getRuntime().addShutdownHook(new Thread(() ->
      Optional.ofNullable(clientCache).ifPresent(cache -> cache.close(keepAlive)),
      "GemFire ClientCache Shutdown Hook"));

    return clientCache;
  }

  ClientCache chatRegion(ClientCache gemfireCache) {

    ClientRegionFactory<String, Chat> chatRegionFactory =
      gemfireCache.createClientRegionFactory(ClientRegionShortcut.PROXY);

    chatRegionFactory.setKeyConstraint(String.class);
    chatRegionFactory.setValueConstraint(Chat.class);
    chatRegionFactory.create(CHAT_REGION_NAME);

    return gemfireCache;
  }

  ClientCache registerContinuousQuery(ClientCache gemfireCache) throws Exception {

    QueryService queryService = resolveQueryService(gemfireCache);

    CqAttributesFactory cqAttributesFactory = new CqAttributesFactory();

    cqAttributesFactory.addCqListener(new CqListenerAdapter() {

      @Override
      public void onEvent(CqEvent event) {
        log((Chat) event.getNewValue());
      }
    });

    CqQuery query = queryService.newCq("NativeChatReceiver", CONTINUOUS_QUERY,
      cqAttributesFactory.create(), DURABLE);

    query.execute();

    return gemfireCache;
  }

  QueryService resolveQueryService(RegionService regionService) {

    return Optional.ofNullable(PoolManager.find(POOL_NAME))
      .map(Pool::getQueryService)
      .orElseGet(regionService::getQueryService);
  }

  ClientCache postProcess(ClientCache gemfireCache) {
    gemfireCache.readyForEvents();
    return gemfireCache;
  }

  abstract class CqListenerAdapter implements CqListener {

    @Override
    public void onError(CqEvent event) {
      onEvent(event);
    }

    @Override
    public void close() {
    }
  }
}
