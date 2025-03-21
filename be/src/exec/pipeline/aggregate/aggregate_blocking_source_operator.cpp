// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "aggregate_blocking_source_operator.h"

#include <variant>

#include "exec/exec_node.h"

namespace starrocks::pipeline {

bool AggregateBlockingSourceOperator::has_output() const {
    return _aggregator->is_sink_complete() && !_aggregator->is_ht_eos();
}

bool AggregateBlockingSourceOperator::is_finished() const {
    return _aggregator->is_sink_complete() && _aggregator->is_ht_eos();
}

Status AggregateBlockingSourceOperator::set_finished(RuntimeState* state) {
    auto notify = _aggregator->defer_notify_sink();
    return _aggregator->set_finished();
}

void AggregateBlockingSourceOperator::close(RuntimeState* state) {
    _aggregator->unref(state);
    SourceOperator::close(state);
}

Status AggregateBlockingSourceOperator::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(SourceOperator::prepare(state));
    _aggregator->attach_source_observer(state, this->_observer);
    return Status::OK();
}

StatusOr<ChunkPtr> AggregateBlockingSourceOperator::pull_chunk(RuntimeState* state) {
    RETURN_IF_CANCELLED(state);

    const auto chunk_size = state->chunk_size();
    ChunkPtr chunk = std::make_shared<Chunk>();

    if (_aggregator->is_none_group_by_exprs()) {
        RETURN_IF_ERROR(_aggregator->convert_to_chunk_no_groupby(&chunk));
    } else {
        RETURN_IF_ERROR(_aggregator->convert_hash_map_to_chunk(chunk_size, &chunk));
    }

    const int64_t old_size = chunk->num_rows();

    // For having
    // if current AggregateBlockSourceOperator is a pre-cache operator, the output format of aggregation columns
    // is binary column(for an example: multi_count_distinct) generated by AggregationFunction::serialize_column,
    // so having predicates should not be applied to the partial and incomplete result.
    if (!_aggregator->is_pre_cache()) {
        eval_runtime_bloom_filters(chunk.get());
        RETURN_IF_ERROR(eval_conjuncts_and_in_filters(_aggregator->conjunct_ctxs(), chunk.get()));
    }
    _aggregator->update_num_rows_returned(-(old_size - static_cast<int64_t>(chunk->num_rows())));
    DCHECK_CHUNK(chunk);
    return std::move(chunk);
}

} // namespace starrocks::pipeline
