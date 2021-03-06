/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.hive.llap.daemon.registry;

import java.util.Map;

import org.apache.hadoop.yarn.api.records.Resource;

public interface ServiceInstance {

  /**
   * Worker identity is a UUID (unique across restarts), to identify a node which died & was brought
   * back on the same host/port
   */
  public String getWorkerIdentity();

  /**
   * Hostname of the service instance
   * 
   * @return
   */
  public String getHost();

  /**
   * RPC Endpoint for service instance
   * 
   * @return
   */
  public int getRpcPort();

  /**
   * Shuffle Endpoint for service instance
   * 
   * @return
   */
  public int getShufflePort();

  /**
   * Return the last known state (without refreshing)
   * 
   * @return
   */

  public boolean isAlive();

  /**
   * Config properties of the Service Instance (llap.daemon.*)
   * 
   * @return
   */

  public Map<String, String> getProperties();

  /**
   * Memory and Executors available for the LLAP tasks
   * 
   * This does not include the size of the cache or the actual vCores allocated via Slider.
   * 
   * @return
   */
  public Resource getResource();
}
