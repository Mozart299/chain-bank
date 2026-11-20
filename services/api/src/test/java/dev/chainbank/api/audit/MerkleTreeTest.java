package dev.chainbank.api.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

class MerkleTreeTest {

    static final HexFormat HEX = HexFormat.of();

    /** Leaf inputs from the Certificate Transparency reference test suite (RFC 6962). */
    static final String[] LEAVES = { "", "00", "10", "2021", "3031", "40414243", "5051525354555657",
            "606162636465666768696a6b6c6d6e6f" };

    /** Expected tree heads for the first 1..8 leaves. */
    static final String[] ROOTS = {
            "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
            "fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125",
            "aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77",
            "d37ee418976dd95753c1c73862b9398fa2a2cf9b4ff0fdfe8b30cd95209614b7",
            "4e3bbb1f7b478dcfe71fb631631519a3bca12c9aefca1612bfce4c13a86264d4",
            "76e67dadbcdf1e10e1b74ddc608abd2f98dfb16fbce75277b5232a127f2087ef",
            "ddb89be403809e325750d3d263cd78929c2942b7942a34b77e122c9594a74c8c",
            "5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328" };

    static List<byte[]> leaves(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(HEX.parseHex(LEAVES[i]));
        }
        return out;
    }

    @Test
    void matchesRfc6962ReferenceVectors() {
        for (int n = 1; n <= 8; n++) {
            assertEquals(ROOTS[n - 1], HEX.formatHex(MerkleTree.root(leaves(n))), "tree of " + n);
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HEX.formatHex(MerkleTree.root(List.of())), "empty tree is SHA256 of nothing");
    }

    @Test
    void everyInclusionProofVerifies() {
        for (int n = 1; n <= 8; n++) {
            List<byte[]> l = leaves(n);
            byte[] root = MerkleTree.root(l);
            for (int i = 0; i < n; i++) {
                assertTrue(MerkleTree.verify(l.get(i), i, n, MerkleTree.proof(l, i), root), "leaf " + i + " of " + n);
            }
        }
    }

    @Test
    void tamperedLeafOrWrongPositionFails() {
        List<byte[]> l = leaves(7);
        byte[] root = MerkleTree.root(l);
        List<byte[]> proof = MerkleTree.proof(l, 3);
        assertFalse(MerkleTree.verify(HEX.parseHex("2022"), 3, 7, proof, root), "changed leaf");
        assertFalse(MerkleTree.verify(l.get(3), 2, 7, proof, root), "wrong index");
        // Note: the root alone does not bind the tree size (leaf 3's path is identical in trees of 7 and 8).
        // CT signs the size with the root; ChainBank binds it on-chain via the batch's first/last event ids.

        List<byte[]> changed = new ArrayList<>(l);
        changed.set(5, HEX.parseHex("ff"));
        assertNotEquals(HEX.formatHex(root), HEX.formatHex(MerkleTree.root(changed)));
    }

    @Test
    void noSecondPreimageFromDuplicatingTheLastLeaf() {
        // Bitcoin-style trees give [a,b,c] and [a,b,c,c] the same root (CVE-2012-2459). RFC 6962 does not.
        List<byte[]> three = leaves(3);
        List<byte[]> four = new ArrayList<>(three);
        four.add(three.get(2));
        assertNotEquals(HEX.formatHex(MerkleTree.root(three)), HEX.formatHex(MerkleTree.root(four)));
    }
}
