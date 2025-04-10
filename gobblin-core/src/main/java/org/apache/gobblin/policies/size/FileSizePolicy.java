package org.apache.gobblin.policies.size;

import org.apache.gobblin.configuration.State;
import org.apache.gobblin.qualitychecker.task.TaskLevelPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A task-level policy that checks if the destination file size is within an acceptable range
 * of the source file size.
 */
public class FileSizePolicy extends TaskLevelPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(FileSizePolicy.class);
    
    // Configuration keys
    public static final String SOURCE_FILE_SIZE_KEY = "source.file.size.bytes";
    public static final String DEST_FILE_SIZE_KEY = "dest.file.size.bytes";

    public FileSizePolicy(State state, Type type) {
        super(state, type);
    }

    @Override
    public Result executePolicy() {
        if (!getTaskState().contains(SOURCE_FILE_SIZE_KEY) || !getTaskState().contains(DEST_FILE_SIZE_KEY)) {
            LOG.warn("Missing file size information in task state. Cannot perform file size check.");
            return Result.FAILED;
        }

        long sourceSize = getTaskState().getPropAsLong(SOURCE_FILE_SIZE_KEY);
        long destSize = getTaskState().getPropAsLong(DEST_FILE_SIZE_KEY);

        if (sourceSize <= 0 || destSize <= 0) {
            LOG.warn("Invalid file sizes detected. Source size: {}, Dest size: {}", sourceSize, destSize);
            return Result.FAILED;
        }

        double sizeDifference = Math.abs(destSize - sourceSize);

        LOG.info("File size check - Source: {} bytes, Destination: {} bytes ", 
            sourceSize, destSize);

        if (sizeDifference > 0) {
            LOG.error("File size in source and destination does not match");
            return Result.FAILED;
        }

        return Result.PASSED;
    }
} 