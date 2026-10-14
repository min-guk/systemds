/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/** The finite movement policy. Aliases never create a new native value origin. */
public final class MemoMovement {
	private MemoMovement() { }
	public enum Kind { DIRECT, UPLOAD, COLLECT, IMPOSSIBLE }
	public static Kind kind(MemoPartition source,MemoPartition required,boolean backedge) {
		if(source == null || required == null) throw new IllegalArgumentException("Layout is unresolved");
		if(source == required) return Kind.DIRECT;
		if(backedge) return Kind.IMPOSSIBLE;
		if(source == MemoPartition.LOCAL) return Kind.UPLOAD;
		if(required == MemoPartition.LOCAL) return Kind.COLLECT;
		return Kind.IMPOSSIBLE; // No RESHARD, including collect+upload of one native origin.
	}
	public static Truth legal(MemoPartition source,MemoPartition target,DataType dataType,
		Privacy sourcePrivacy,long rows,long cols,int workers,boolean backedge) {
		if(source == null || target == null) return Truth.UNKNOWN;
		Kind kind = kind(source,target,backedge);
		if(kind == Kind.IMPOSSIBLE) return Truth.NO;
		if(kind == Kind.DIRECT) return Truth.YES;
		if(dataType != DataType.MATRIX && dataType != DataType.FRAME) return Truth.NO;
		if(kind == Kind.COLLECT) return sourcePrivacy == null ? Truth.UNKNOWN
			: protectedPayload(sourcePrivacy) ? Truth.NO : Truth.YES;
		if(workers <= 0) return Truth.NO;
		if(rows <= 0 || cols <= 0) return Truth.UNKNOWN;
		if(rows > Integer.MAX_VALUE || cols > Integer.MAX_VALUE) return Truth.NO;
		if(target == MemoPartition.ROW && rows < workers || target == MemoPartition.COL && cols < workers)
			return Truth.NO;
		return Truth.YES;
	}
	public static boolean protectedPayload(Privacy privacy) {
		return privacy == Privacy.PRIVATE || privacy == Privacy.PRIVATE_AGGREGATE;
	}
}
