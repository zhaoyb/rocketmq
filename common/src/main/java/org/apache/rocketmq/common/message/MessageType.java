/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.common.message;

public enum MessageType {
    /**
     * 普通消息
     */
    Normal_Msg,
    /**
     * 事物消息-half,类似于只发送 不提交
     */
    Trans_Msg_Half,
    /**
     * 事物消息-commit
     */
    Trans_msg_Commit,

    /**
     * 延迟消息
     */
    Delay_Msg,
}
