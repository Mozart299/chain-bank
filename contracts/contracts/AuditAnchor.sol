// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";

/**
 * @title ChainBank audit anchor
 * @notice Public, append-only commitments to batches of the bank's hash-chained audit log
 *         (ADR 0007). Each batch is a contiguous range of audit event ids and the RFC 6962 Merkle
 *         root over their hashes. Once written, a batch can never change. Anyone holding an
 *         exported batch can recompute the root and compare it with what is stored here.
 *
 * Invariants enforced on-chain:
 *  - batch ids are sequential (no gaps, no reordering);
 *  - event ranges are contiguous across batches (no events silently skipped);
 *  - a committed batch is immutable.
 */
contract AuditAnchor is AccessControl {
    bytes32 public constant COMMITTER_ROLE = keccak256("COMMITTER_ROLE");

    struct Batch {
        bytes32 root;
        uint64 firstEventId;
        uint64 lastEventId;
        uint64 blockNumber;
    }

    mapping(uint256 => Batch) public batches;
    uint256 public lastBatchId;

    event BatchCommitted(uint256 indexed batchId, uint64 firstEventId, uint64 lastEventId, bytes32 root);

    error OutOfOrder(uint256 expectedBatchId);
    error NotContiguous(uint64 expectedFirstEventId);
    error InvalidBatch();

    constructor(address admin) {
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
    }

    function commit(uint256 batchId, uint64 firstEventId, uint64 lastEventId, bytes32 root)
        external
        onlyRole(COMMITTER_ROLE)
    {
        if (batchId != lastBatchId + 1) revert OutOfOrder(lastBatchId + 1);
        if (root == bytes32(0) || firstEventId == 0 || lastEventId < firstEventId) revert InvalidBatch();
        if (lastBatchId > 0) {
            uint64 expected = batches[lastBatchId].lastEventId + 1;
            if (firstEventId != expected) revert NotContiguous(expected);
        }
        batches[batchId] = Batch(root, firstEventId, lastEventId, uint64(block.number));
        lastBatchId = batchId;
        emit BatchCommitted(batchId, firstEventId, lastEventId, root);
    }
}
