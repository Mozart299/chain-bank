// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {ERC20Capped} from "@openzeppelin/contracts/token/ERC20/extensions/ERC20Capped.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";

/**
 * @title ChainBank settlement token (simulation only)
 * @notice Fictional interbank settlement tokens for the ChainBank learning sandbox. A token
 *         movement here is NOT a transfer of UGX or of central-bank reserves (spec section 8.3).
 *
 * Design (ADR 0006):
 *  - Permissioned. Only registered institutions hold tokens, and only the SETTLER (the bank
 *    backend's gateway) moves them, through `settle`. Ordinary ERC-20 transfers and approvals
 *    are disabled.
 *  - Every settlement carries a unique id derived from the off-chain transfer. Replaying an id
 *    reverts, so a retried or duplicated submission can never move funds twice.
 *  - Events carry only institution pseudonyms and amounts, never customer data.
 *  - Non-upgradeable by design; a new version means a new deployment plus migration.
 */
contract SettlementToken is ERC20Capped, AccessControl, Pausable {
    bytes32 public constant MINTER_ROLE = keccak256("MINTER_ROLE");
    bytes32 public constant SETTLER_ROLE = keccak256("SETTLER_ROLE");
    bytes32 public constant PAUSER_ROLE = keccak256("PAUSER_ROLE");

    /// Institution pseudonym (e.g. "INST-A" as bytes32) => settlement wallet.
    mapping(bytes32 => address) public walletOf;
    /// Settlement ids that have already been executed.
    mapping(bytes32 => bool) public settled;

    event InstitutionRegistered(bytes32 indexed pseudonym, address wallet);
    event Settled(bytes32 indexed settlementId, bytes32 indexed fromInstitution, bytes32 indexed toInstitution, uint256 amount);

    error TransfersDisabled();
    error AlreadySettled(bytes32 settlementId);
    error UnknownInstitution(bytes32 pseudonym);
    error InstitutionAlreadyRegistered(bytes32 pseudonym);
    error InvalidSettlement();

    constructor(address admin, uint256 cap_) ERC20("ChainBank Settlement xUGX", "sxUGX") ERC20Capped(cap_) {
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(MINTER_ROLE, admin);
        _grantRole(PAUSER_ROLE, admin);
    }

    /// xUGX has no minor unit.
    function decimals() public pure override returns (uint8) {
        return 0;
    }

    function registerInstitution(bytes32 pseudonym, address wallet) external onlyRole(DEFAULT_ADMIN_ROLE) {
        if (pseudonym == bytes32(0) || wallet == address(0)) revert InvalidSettlement();
        if (walletOf[pseudonym] != address(0)) revert InstitutionAlreadyRegistered(pseudonym);
        walletOf[pseudonym] = wallet;
        emit InstitutionRegistered(pseudonym, wallet);
    }

    /// Prefunds an institution's settlement position (bounded by the cap).
    function mint(bytes32 institution, uint256 amount) external onlyRole(MINTER_ROLE) {
        _mint(_walletOf(institution), amount);
    }

    /// Moves `amount` from one institution to another for the off-chain transfer identified by `settlementId`.
    function settle(bytes32 settlementId, bytes32 fromInstitution, bytes32 toInstitution, uint256 amount)
        external
        onlyRole(SETTLER_ROLE)
        whenNotPaused
    {
        if (settled[settlementId]) revert AlreadySettled(settlementId);
        if (settlementId == bytes32(0) || amount == 0 || fromInstitution == toInstitution) revert InvalidSettlement();
        address from = _walletOf(fromInstitution);
        address to = _walletOf(toInstitution);
        settled[settlementId] = true;
        _transfer(from, to, amount);
        emit Settled(settlementId, fromInstitution, toInstitution, amount);
    }

    function pause() external onlyRole(PAUSER_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(PAUSER_ROLE) {
        _unpause();
    }

    // --- Permissioned: no free-floating ERC-20 movement -------------------------------------------

    function transfer(address, uint256) public pure override returns (bool) {
        revert TransfersDisabled();
    }

    function transferFrom(address, address, uint256) public pure override returns (bool) {
        revert TransfersDisabled();
    }

    function approve(address, uint256) public pure override returns (bool) {
        revert TransfersDisabled();
    }

    function _update(address from, address to, uint256 value) internal override whenNotPaused {
        super._update(from, to, value);
    }

    function _walletOf(bytes32 pseudonym) private view returns (address wallet) {
        wallet = walletOf[pseudonym];
        if (wallet == address(0)) revert UnknownInstitution(pseudonym);
    }
}
