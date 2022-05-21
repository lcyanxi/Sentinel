/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.statistic;

import java.util.Collection;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource ID.</li>
 * <li>Origin node: statistics of a cluster node from different callers/origins.</li>
 * <li>{@link DefaultNode}: statistics for specific resource name in the specific context.</li>
 * <li>Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
@Spi(order = Constants.ORDER_STATISTIC_SLOT)
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    /**
     * 负责统计实时调用数据，包括运行信息（访问次数、线程数、cpu）、来源信息等
     */
    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            // 放行到下一个 slot 做限流、降级等判断
            fireEntry(context, resourceWrapper, node, count, prioritized, args);

            // 请求通过了,线程计数器 +1 用作线程隔离
            node.increaseThreadNum();
            // 请求计数器 +1 用作限流
            node.addPassRequest(count);

            if (context.getCurEntry().getOriginNode() != null) {
                // 如果有 origin，来源计数器也都要 +1
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // 如果是入口资源，还要给全局计数器 +1
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }

            // 请求通过后的回调
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (PriorityWaitException ex) {
            node.increaseThreadNum();
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
            }
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (BlockException e) {
            // Blocked, set block exception to current entry.
            context.getCurEntry().setBlockError(e);

            // Add block count.
            node.increaseBlockQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

            // Handle block event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            // Unexpected internal error, set error to current entry.
            context.getCurEntry().setError(e);

            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        Node node = context.getCurNode();

        if (context.getCurEntry().getBlockError() == null) {
            // Calculate response time (use completeStatTime as the time of completion).
            long completeStatTime = TimeUtil.currentTimeMillis();
            context.getCurEntry().setCompleteTimestamp(completeStatTime);
            long rt = completeStatTime - context.getCurEntry().getCreateTimestamp();

            Throwable error = context.getCurEntry().getError();

            // Record response time and success count.
            recordCompleteFor(node, count, rt, error);
            recordCompleteFor(context.getCurEntry().getOriginNode(), count, rt, error);
            if (resourceWrapper.getEntryType() == EntryType.IN) {
                recordCompleteFor(Constants.ENTRY_NODE, count, rt, error);
            }
        }

        // Handle exit event with registered exit callback handlers.
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        // fix bug https://github.com/alibaba/Sentinel/issues/2374
        fireExit(context, resourceWrapper, count, args);
    }

    private void recordCompleteFor(Node node, int batchCount, long rt, Throwable error) {
        if (node == null) {
            return;
        }
        node.addRtAndSuccess(rt, batchCount);
        node.decreaseThreadNum();

        if (error != null && !(error instanceof BlockException)) {
            node.increaseExceptionQps(batchCount);
        }
    }
}
