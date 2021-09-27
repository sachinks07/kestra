package io.kestra.core.services;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.TaskDefault;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.RunContextLogger;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.MapUtils;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class TaskDefaultService {
    @Nullable
    @Inject
    protected TaskGlobalDefaultConfiguration globalDefault;

    @Inject
    @Named(QueueFactoryInterface.WORKERTASKLOG_NAMED)
    protected QueueInterface<LogEntry> logQueue;

    /**
     * @param flow the flow to extract default
     * @return list of {@code TaskDefault} order by most important first
     */
    protected List<TaskDefault> mergeAllDefaults(Flow flow) {
        List<TaskDefault> list = new ArrayList<>();

        if (globalDefault != null && globalDefault.getDefaults() != null) {
            list.addAll(globalDefault.getDefaults());
        }

        if (flow.getTaskDefaults() != null) {
            list.addAll(flow.getTaskDefaults());
        }

        return list;
    }

    private static Map<String, List<TaskDefault>> taskDefaultsToMap(List<TaskDefault> taskDefaults) {
        return taskDefaults
            .stream()
            .sorted(Comparator.comparingInt(value -> value.isForced() ? 1 : 0))
            .collect(Collectors.groupingBy(TaskDefault::getType));
    }

    public Flow injectDefaults(Flow flow, Execution execution) {
        try {
            return this.injectDefaults(flow);
        } catch (Exception e) {
            RunContextLogger
                .logEntries(
                    Execution.loggingEventFromException(e),
                    LogEntry.of(execution)
                )
                .forEach(logQueue::emit);
            return flow;
        }
    }

    public Flow injectDefaults(Flow flow, Logger logger) {
        try {
            return this.injectDefaults(flow);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            return flow;
        }
    }

    @SuppressWarnings("unchecked")
    Flow injectDefaults(Flow flow) {
        Map<String, Object> flowAsMap = JacksonMapper.toMap(flow);

        Map<String, List<TaskDefault>> defaults = taskDefaultsToMap(mergeAllDefaults(flow));

        Object taskDefaults = flowAsMap.get("taskDefaults");
        if (taskDefaults != null) {
            flowAsMap.remove("taskDefaults");
        }

        Map<String, Object> flowAsMapWithDefault = (Map<String, Object>) recursiveDefaults(flowAsMap, defaults);

        if (taskDefaults != null) {
            flowAsMapWithDefault.put("taskDefaults", taskDefaults);
        }

        return JacksonMapper.toMap(flowAsMapWithDefault, Flow.class);
    }

    private static Object recursiveDefaults(Object object, Map<String, List<TaskDefault>> defaults) {
        if (object instanceof Map) {
            Map<?, ?> value = (Map<?, ?>) object;
            if (value.containsKey("type")) {
                value = defaults(value, defaults);
            }

            return value
                .entrySet()
                .stream()
                .map(e -> new AbstractMap.SimpleEntry<>(
                    e.getKey(),
                    recursiveDefaults(e.getValue(), defaults)
                ))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else if (object instanceof Collection) {
            Collection<?> value = (Collection<?>) object;
            return value
                .stream()
                .map(r -> recursiveDefaults(r, defaults))
                .collect(Collectors.toList());
        } else {
            return object;
        }
    }

    @SuppressWarnings("unchecked")
    protected static Map<?, ?> defaults(Map<?, ?> task, Map<String, List<TaskDefault>> defaults) {
        Object type = task.get("type");
        if (!(type instanceof String)) {
            return task;
        }

        String taskType = (String) type;

        if (!defaults.containsKey(taskType)) {
            return task;
        }

        Map<String, Object> result = (Map<String, Object>) task;

        for (TaskDefault taskDefault : defaults.get(taskType)) {
            if (taskDefault.isForced()) {
                result = MapUtils.merge(result, taskDefault.getValues());
            } else {
                result = MapUtils.merge(taskDefault.getValues(), result);
            }
        }

        return result;
    }
}
