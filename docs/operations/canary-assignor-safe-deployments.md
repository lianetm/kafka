# Canary Assignor For Safe Consumer Deployments

This document describes a custom server-side partition assignor for Apache Kafka that enables canary deployments for consumer applications.

## Overview

The **Canary Assignor** leverages KIP-848's server-side assignment to implement controlled traffic splitting within a single consumer group. Canary members receive a small percentage of partitions (e.g., 10%), allowing safe validation of new consumer versions before full rollout.

## The Problem

Deploying new consumer application versions is risky:

- **Rolling deployment**: Blast radius is 1/N of traffic per instance (e.g., 25% with 4 consumers)
- **Bugs in new version**: Affects all partitions assigned to that consumer
- **Rollback time**: Minutes of degraded processing while restarting
- **Testing in production**: No way to limit exposure to real traffic


## The Solution: Canary Assignor

A server-side assignor that:
1. Identifies "canary" members via `client.rack=canary`
2. Assigns only ~10% of partitions to canary members
3. Distributes remaining 90% to regular members
4. Uses the same balanced quota-based distribution as the default UniformAssignor within each pool

### Architecture

```
                     Topic: orders (20 partitions)
 ┌─────────────────────────────────────────────────────────────┐
 │  P0  P1  P2  P3  P4  P5  P6  P7  P8  P9  ... P19            │
 │   │   │   │   │   │   │   │   │   │   │       │             │
 │   └───┴───┘   └───┴───┴───┴───┴───┴───┴───────┘             │
 │       │                       │                              │
 │       ▼                       ▼                              │
 │  ┌─────────┐           ┌─────────────────────────────────┐  │
 │  │ CANARY  │           │           REGULAR               │  │
 │  │  (10%)  │           │            (90%)                │  │
 │  │         │           │                                 │  │
 │  │ v2.0    │           │  v1.0    v1.0    v1.0    v1.0   │  │
 │  │ 2 parts │           │  4-5 partitions each            │  │
 │  └─────────┘           └─────────────────────────────────┘  │
 └─────────────────────────────────────────────────────────────┘
```

### Goals

1. **Control blast radius**: Limit canary exposure to ~10% of traffic
2. **Require no infrastructure changes**: Use the same topic and consumer group
3. **Keep configuration simple**: Identify canary members via `client.rack=canary`
4. **Enable progressive delivery**: Support gradual rollout (10% -> 25% -> 50% -> 100%)

## Implementation Details

### Identifying Canary Members

The assignor uses `client.rack` configuration to identify canary members:

| Member Type | Consumer Config |
|-------------|-----------------|
| Canary | `client.rack=canary` |
| Regular | `client.rack=<any other value>` |

Using `client.rack` to represent a logical topology (canary), while still representing the physical topology as traditionally used (e.g., availability zones).

### Assignment Algorithm

```
1. Partition members into canary vs regular pools based on client.rack
2. Calculate canary partition count: max(1, ceil(totalPartitions * canaryPercentage))
3. Handle edge cases:
   - No canary members: 100% to regular (behaves like UniformAssignor)
   - No regular members: 100% to canary (fallback)
4. Assign canary partitions to canary members using balanced quota distribution
5. Assign regular partitions to regular members using balanced quota distribution

Within each pool, partitions are distributed using the same algorithm as UniformAssignor:
- Each member receives a minimum quota: totalPartitions / numberOfMembers
- Remaining partitions (totalPartitions % numberOfMembers) are distributed one per member
- This ensures the difference in partition count between any two members is at most one
```

### Default Values

| Parameter | Value | Description |
|-----------|-------|-------------|
| Canary percentage | 0.10 (10%) | Fraction of partitions for canary pool |
| Canary rack ID | "canary" | Rack identifier for canary members (via `client.rack`) |

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| No canary members | 100% to regular members (standard uniform) |
| No regular members | 100% to canary members (fallback) |
| Canary % < 1 partition | At least 1 partition to canary |
| Single partition topic | Goes to canary if canary exists |

## Demo: Progressive Canary Deployment

### Prerequisites

- Kafka cluster with canary assignor configured
- Topic `orders` with 20 partitions
- Monitoring/metrics collection (Grafana, etc.)

### Broker Configuration

```properties
# server.properties - canary assignor is available but not the default
group.consumer.assignors=uniform,range,canary
```

### Step 1: Stable State (v1.0)

Start 4 consumers running v1.0:

```bash
# All consumers with production rack
bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic orders \
  --group order-processor \
  --consumer-property client.rack=us-east-1a
```

Assignment (uniform):
```
Consumer-1 (rack=us-east-1a): partitions [0,1,2,3,4]
Consumer-2 (rack=us-east-1a): partitions [5,6,7,8,9]
Consumer-3 (rack=us-east-1a): partitions [10,11,12,13,14]
Consumer-4 (rack=us-east-1a): partitions [15,16,17,18,19]
```

### Step 2: Deploy Canary (v2.0)

Start 1 new consumer with v2.0 and canary rack:

```bash
# Canary consumer
bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic orders \
  --group order-processor \
  --consumer-property client.rack=canary
```

New assignment (canary assignor):
```
Consumer-5 (rack=canary):     partitions [0,1]           # 10% = 2 partitions
Consumer-1 (rack=us-east-1a): partitions [2,3,4,5,6]     # Remaining 90%
Consumer-2 (rack=us-east-1a): partitions [7,8,9,10]
Consumer-3 (rack=us-east-1a): partitions [11,12,13,14,15]
Consumer-4 (rack=us-east-1a): partitions [16,17,18,19]
```

### Step 3: Monitor Canary

Monitor the canary consumer and decide whether to proceed with rollout or roll back.

### Step 4a: Expand Canary (Success Path)

Gradually promote more consumers to canary:

```bash
# Restart Consumer-1 with v2.0 and canary rack
# Now 2 canary members -> ~20% traffic

Consumer-1 (rack=canary):     partitions [0,1]
Consumer-5 (rack=canary):     partitions [2,3]
Consumer-2 (rack=us-east-1a): partitions [4,5,6,7,8,9]
Consumer-3 (rack=us-east-1a): partitions [10,11,12,13,14]
Consumer-4 (rack=us-east-1a): partitions [15,16,17,18,19]
```

Continue until all consumers are on v2.0.

### Step 4b: Rollback (Failure Path)

If canary shows problems:

```bash
# Simply stop the canary consumer
# Partitions automatically reassigned to healthy v1.0 consumers

Consumer-1 (rack=us-east-1a): partitions [0,1,2,3,4]
Consumer-2 (rack=us-east-1a): partitions [5,6,7,8,9]
Consumer-3 (rack=us-east-1a): partitions [10,11,12,13,14]
Consumer-4 (rack=us-east-1a): partitions [15,16,17,18,19]
```

Impact: Only 2 partitions (10%) saw errors, quickly recovered.

### Step 5: Full Rollout Complete

After all consumers promoted to v2.0, optionally change racks back to production values for uniform assignment, or leave as-is if canary percentage is acceptable for ongoing monitoring.

## Future Enhancement: Rack-Awareness

The current implementation uses the same balanced distribution as UniformAssignor within each pool. A future enhancement could add rack-aware assignment for the regular (non-canary) pool:

```
Partition P5 replicas in: [us-east-1a, us-east-1b]
Consumer-2 in rack: us-east-1a

-> Prefer assigning P5 to Consumer-2 (same rack as replica)
-> Reduces cross-AZ network traffic and latency
```

This would provide two benefits in one assignor:
1. **Safety**: Canary traffic splitting
2. **Efficiency**: Rack-aware assignment for production traffic

## Summary

The Canary Assignor demonstrates how KIP-848's pluggable server-side assignment enables new deployment patterns that weren't possible with client-side assignors:

| Feature | Benefit |
|---------|---------|
| Server-side logic | No client code changes for traffic splitting |
| Pluggable interface | Custom assignment strategies per use case |
| Rack metadata | Leverage existing consumer config for canary identification |
| Incremental rebalance | Smooth traffic shifting without stop-the-world |
