
/*
 * Copyright (c) 2008, 2009, Oracle and/or its affiliates. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
// AUTOMATICALLY GENERATED FILE - DO NOT EDIT
package com.termux.shared.file.filesystem;

// BEGIN Android-changed: Use constants from android.system.OsConstants. http://b/32203242
// Those constants are initialized by native code to ensure correctness on different architectures.
import android.system.OsConstants;


/**
 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/sun/nio/fs/UnixConstants.java
 */
public class UnixConstants {
    private UnixConstants() { }

    static final int S_IAMB = get_S_IAMB();

    static final int S_IRUSR = OsConstants.S_IRUSR;

    static final int S_IWUSR = OsConstants.S_IWUSR;

    static final int S_IXUSR = OsConstants.S_IXUSR;

    static final int S_IRGRP = OsConstants.S_IRGRP;

    static final int S_IWGRP = OsConstants.S_IWGRP;

    static final int S_IXGRP = OsConstants.S_IXGRP;

    static final int S_IROTH = OsConstants.S_IROTH;

    static final int S_IWOTH = OsConstants.S_IWOTH;

    static final int S_IXOTH = OsConstants.S_IXOTH;

    static final int S_IFMT = OsConstants.S_IFMT;

    static final int S_IFREG = OsConstants.S_IFREG;

    static final int S_IFDIR = OsConstants.S_IFDIR;

    static final int S_IFLNK = OsConstants.S_IFLNK;

    static final int S_IFSOCK = OsConstants.S_IFSOCK;

    static final int S_IFCHR = OsConstants.S_IFCHR;

    static final int S_IFBLK = OsConstants.S_IFBLK;

    static final int S_IFIFO = OsConstants.S_IFIFO;

    // S_IAMB are access mode bits, therefore, calculated by taking OR of all the read, write and
    // execute permissions bits for owner, group and other.
    private static int get_S_IAMB() {
        return (OsConstants.S_IRUSR | OsConstants.S_IWUSR | OsConstants.S_IXUSR |
            OsConstants.S_IRGRP | OsConstants.S_IWGRP | OsConstants.S_IXGRP |
            OsConstants.S_IROTH | OsConstants.S_IWOTH | OsConstants.S_IXOTH);
    }
    // END Android-changed: Use constants from android.system.OsConstants. http://b/32203242
}
