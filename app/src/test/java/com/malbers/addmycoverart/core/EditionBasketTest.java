package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** 1.1.2: ticks survive switching editions in More editions (Michael: "select 3 from 1 and then 2 from another"). */
public class EditionBasketTest {
    private static List<CaaExtras.Extra> extras(String rel, int n) {
        CaaExtras.Extra[] x = new CaaExtras.Extra[n];
        for (int i = 0; i < n; i++) x[i] = new CaaExtras.Extra("booklet", rel + "-" + i, rel + "-" + i + ".jpg", "t", "s");
        return Arrays.asList(x);
    }

    @Test public void threeFromOneAndTwoFromAnotherSaveTogether() {
        EditionBasket b = new EditionBasket();
        List<CaaExtras.Extra> e1 = extras("A", 5), e2 = extras("B", 4);
        b.park("A", e1, new boolean[]{true, true, false, true, false}, new File[5]);   // 3 from edition A
        boolean[] onB = b.restore("B", 4);
        assertArrayEquals(new boolean[4], onB);                                       // B starts empty
        onB[0] = true; onB[2] = true;                                                 // 2 from edition B
        assertEquals(3, b.countOthers("B"));
        assertEquals(1, b.editionsWithPicks("B"));
        EditionBasket.Picks all = b.combined("B", e2, onB, new File[4]);
        assertEquals(5, all.extras.size());
        assertEquals("A-0", all.extras.get(0).label);
        assertEquals("B-2", all.extras.get(4).label);
        for (boolean s : all.selected) assertTrue(s);
    }

    @Test public void comingBackRestoresTheTicks() {
        EditionBasket b = new EditionBasket();
        b.park("A", extras("A", 3), new boolean[]{false, true, false}, null);
        assertArrayEquals(new boolean[]{false, true, false}, b.restore("A", 3));
        assertEquals(0, b.countOthers("A"));                  // the edition on screen is never double counted
    }

    @Test public void anEditionWithNothingTickedIsForgotten() {
        EditionBasket b = new EditionBasket();
        b.park("A", extras("A", 2), new boolean[]{true, false}, null);
        b.park("A", extras("A", 2), new boolean[]{false, false}, null);   // un-ticked on the way back
        assertEquals(0, b.countOthers("B"));
        b.park("C", extras("C", 1), new boolean[]{true}, null);
        b.clear();
        assertEquals(0, b.countOthers("X"));
    }
}
