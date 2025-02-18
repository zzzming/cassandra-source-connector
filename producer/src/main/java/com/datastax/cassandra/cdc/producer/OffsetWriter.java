/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cassandra.cdc.producer;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Periodically persist the last sent offset to recover from that checkpoint.
 */
public interface OffsetWriter {

    /**
     * Set the current offset.
     * @param mutation
     */
    public void markOffset(Mutation<?> mutation);

    /**
     * Get the current offset.
     * @return
     */
    public CommitLogPosition offset(Optional<UUID> nodeId);

    default  CommitLogPosition offset() {
        return offset(Optional.empty());
    }

    /**
     * Persist the offset
     * @throws IOException
     */
    public void flush(Optional<UUID> nodeId) throws IOException;

    default void flush() throws IOException {
        flush(Optional.empty());
    }
}
