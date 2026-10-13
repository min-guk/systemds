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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.OccurrenceContext;

/** Build immutable native domains once. Transparent value bindings have no native rule. */
public final class MemoRuleBuilder {
	private static final class RuleIntersection {
		private List<RuleMemo.Rule> rules;
		private List<InputRole> roles;
		private RuleIntersection(List<RuleMemo.Rule> rules,List<InputRole> roles) {
			this.rules=rules;
			this.roles=roles;
		}
	}
	/** Immutable rule domains plus request-confined semantic analyses; not shared across planning epochs. */
	public record Result(RuleMemo memo,List<List<InputRole>> inputRoles,List<String> diagnostics,
		MemoShapes shapes,MemoPrivacy privacy) {
		public Result {
			Objects.requireNonNull(memo, "rule memo");
			Objects.requireNonNull(shapes, "shape authority");
			Objects.requireNonNull(privacy, "request privacy analysis");
			inputRoles = inputRoles.stream().map(List::copyOf).toList();
			diagnostics = List.copyOf(diagnostics);
		}
	}
	private MemoRuleBuilder() { }
	public static Result build(MemoProgramFacts facts,MemoSources sources,MemoNativeRules oracle) {
		return build(facts,sources,oracle,MemoShapes.analyze(facts,sources));
	}
	public static Result build(MemoProgramFacts facts,MemoSources sources,MemoNativeRules oracle,
		MemoShapes shapes) {
		shapes.requireRequest(facts,sources);
		List<List<RuleMemo.Rule>> rules = new ArrayList<>();
		List<List<InputRole>> roles = new ArrayList<>();
		List<String> diagnostics = new ArrayList<>();
		List<List<MemoProgramFacts.Occurrence>> occurrencesByOwner = new ArrayList<>();
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) occurrencesByOwner.add(new ArrayList<>());
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			occurrencesByOwner.get(occurrence.compiledOwnerId()).add(occurrence);
		for(MemoShapes.Diagnostic diagnostic : shapes.diagnostics())
			diagnostics.add("shape owner=" + diagnostic.owner() + " binding=" + diagnostic.binding()
				+ " " + diagnostic.code() + " " + diagnostic.message());
		boolean[] complete = new boolean[facts.compiledOwnerCount()]; Arrays.fill(complete,true);
		MemoBindingResolver bindingResolver=new MemoBindingResolver(facts);
		MemoReachableContexts reachableContexts=new MemoReachableContexts(facts,bindingResolver);
		MemoPrivacy privacy = new MemoPrivacy(facts,bindingResolver,sources);
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			final int currentOwner=owner;
			Hop hop = shapes.view(owner);
			List<InputRole> inputRoles = new ArrayList<>();
			for(int i = 0; i < hop.getInput().size(); i++) inputRoles.add(MemoNativeRules.metadataInput(hop,i)
				? InputRole.METADATA : hop.getInput(i).getDataType().isScalar() ? InputRole.SCALAR
				: hop.getInput(i).getDataType().isMatrix() || hop.getInput(i).getDataType().isFrame()
				? InputRole.PAYLOAD : InputRole.CONTROL);
			if(transparent(hop)) { rules.add(List.of()); roles.add(inputRoles); continue; }
			MemoSources.Source source = sources.source(owner);
			if(source != null) {
				rules.add(List.of(new RuleMemo.Rule(ExecType.FED,
					java.util.Collections.nCopies(hop.getInput().size(),MemoPartition.LOCAL),
					List.of(source.partition()),"source/canonical")));
				roles.add(java.util.Collections.nCopies(hop.getInput().size(),InputRole.METADATA));
				continue;
			}
			boolean multiReturnBuiltin=hop instanceof FunctionOp function
				&& function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN;
			if(sources.workers() == 0 && !multiReturnBuiltin || hop instanceof LiteralOp) {
				rules.add(List.of(new RuleMemo.Rule(ExecType.CP,
					java.util.Collections.nCopies(hop.getInput().size(),MemoPartition.LOCAL),
					List.of(MemoPartition.LOCAL),"cp/local")));
				roles.add(inputRoles); continue;
			}
			List<RuleMemo.Rule> commonRules = null;
			List<InputRole> commonRoles = null;
			for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
				List<OccurrenceContext> occurrenceContexts=facts.occurrenceContexts(occurrence.id());
				if(occurrenceContexts.isEmpty()) {
					complete[owner]=false;
					diagnostics.add("owner="+owner+" occurrence="+occurrence.id()
						+" has no reachable call frame");
					continue;
				}
				for(OccurrenceContext occurrenceContext : occurrenceContexts) {
					EvaluationContext base=new EvaluationContext(Map.of(),Map.of(),
						MemoBindingResolver.NO_SELECTION,4096,occurrenceContext.callFrame(),Map.of());
					RuleIntersection intersection=new RuleIntersection(commonRules,commonRoles);
					boolean[] unresolvedShape={false};
					MemoBindingResolver.VisitResult activation=reachableContexts.visit(occurrence,base,active -> {
						if(!visitShapeContexts(shapes,occurrence.id(),active,context -> {
							Hop occurrenceHop=shapes.viewAt(occurrence.id(),context);
							MemoNativeRules.Result nativeRules=oracle.rules(occurrenceHop,Math.max(1,sources.workers()),
								shapes.hintAt(occurrence.id(),context));
							if(intersection.rules==null) intersection.rules=new ArrayList<>(nativeRules.rules());
							else intersection.rules.retainAll(nativeRules.rules());
							if(intersection.roles==null) intersection.roles=nativeRules.inputRoles();
							else if(!intersection.roles.equals(nativeRules.inputRoles())) {
								complete[currentOwner]=false;
								diagnostics.add("owner="+currentOwner+" occurrence="+occurrence.id()
									+" inconsistent input roles across shape contexts");
							}
							complete[currentOwner] &= nativeRules.complete();
							for(var diagnostic : nativeRules.coverageGaps()) diagnostics.add("owner="+currentOwner
								+" occurrence="+occurrence.id()+" hop="+hop.getHopID()+" op="
								+hop.getOpString()+" "+diagnostic);
						})) unresolvedShape[0]=true;
						return true;
					});
					if(activation.status()==MemoBindingResolver.VisitStatus.UNRESOLVED || unresolvedShape[0]) {
						complete[owner]=false;
						diagnostics.add("owner="+owner+" occurrence="+occurrence.id()
							+" unresolved reachable shape context: "+activation.detail());
					}
					commonRules=intersection.rules;
					commonRoles=intersection.roles;
				}
			}
			if(commonRules == null) {
				MemoNativeRules.Result nativeRules = oracle.rules(hop,Math.max(1,sources.workers()),shapes.hint(owner));
				commonRules = new ArrayList<>(nativeRules.rules());
				commonRoles = nativeRules.inputRoles();
				complete[owner] &= nativeRules.complete();
			}
			List<InputRole> selectedRoles = commonRoles == null ? inputRoles : commonRoles;
			List<RuleMemo.Rule> privacyFiltered = new ArrayList<>(commonRules.size());
			for(RuleMemo.Rule rule : commonRules) {
				MemoPrivacy.RuleCheck rejection = null;
				int rejectedOccurrence = -1;
				for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
					if(!sourceGroundedPrivacy(occurrence,selectedRoles,facts,sources)) continue;
					MemoPrivacy.RuleCheck check = privacy.unconditionalRuleLegalityAt(
						occurrence.id(),rule,selectedRoles,MemoPrivacy.Context.empty());
					if(check.legality() == MemoPrivacy.Legality.ILLEGAL) {
						rejection = check;
						rejectedOccurrence = occurrence.id();
						break;
					}
				}
				if(rejection == null) privacyFiltered.add(rule);
				else diagnostics.add("privacy-pruned owner=" + owner + " occurrence="
					+ rejectedOccurrence + " variant=" + rule.variant() + " reason="
					+ rejection.reason());
			}
			rules.add(List.copyOf(privacyFiltered));
			roles.add(selectedRoles);
		}
		return new Result(new RuleMemo(rules,complete),roles,diagnostics,shapes,privacy);
	}

	private static boolean sourceGroundedPrivacy(MemoProgramFacts.Occurrence occurrence,
		List<InputRole> roles,MemoProgramFacts facts,MemoSources sources) {
		for(int position=0; position<roles.size(); position++) {
			int binding=facts.inputBinding(occurrence.id(),position);
			if(!sourceGroundedPrivacy(binding,facts,sources,new java.util.BitSet())) return false;
		}
		return true;
	}

	private static boolean sourceGroundedPrivacy(int binding,MemoProgramFacts facts,
		MemoSources sources,java.util.BitSet active) {
		if(binding<0 || binding>=facts.bindings().size() || active.get(binding)) return false;
		active.set(binding);
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()!=MemoProgramFacts.BindingKind.DEFINITION) return false;
		// Versioned transient definitions point at their exact source binding. A native
		// value definition has no child and references its exact occurrence directly.
		if(node.children().size()==1)
			return sourceGroundedPrivacy(node.children().get(0),facts,sources,active);
		if(!node.children().isEmpty() || node.referenceId()<0
			|| node.referenceId()>=facts.occurrences().size()) return false;
		MemoProgramFacts.Occurrence source=facts.occurrence(node.referenceId());
		Hop sourceHop=source.hop();
		if(sourceHop instanceof LiteralOp) return true;
		return sourceHop instanceof DataOp data
			&& (data.getOp()==OpOpData.FEDERATED || data.getOp()==OpOpData.PERSISTENTREAD
				|| data.getOp()==OpOpData.SQLREAD)
			&& sources.privacy(source.compiledOwnerId())!=null;
	}

	/** Project shape-relevant guards only after activation has fixed correlated control decisions. */
	private static boolean visitShapeContexts(MemoShapes shapes,int occurrence,EvaluationContext context,
		Consumer<EvaluationContext> visitor) {
		MemoShapes.CostGuardSearch search=shapes.firstUnfixedCostGuard(occurrence,context);
		if(search.unresolved()) return false;
		MemoBindingResolver.GuardDecision guard=search.guard();
		if(guard==null) {
			visitor.accept(context);
			return true;
		}
		boolean complete=true;
		for(boolean value : new boolean[] {false,true}) {
			Map<MemoBindingResolver.GuardDecision,Boolean> guards=
				new java.util.LinkedHashMap<>(context.guards());
			guards.put(guard,value);
			EvaluationContext next=new EvaluationContext(guards,context.loops(),context.selections(),
				context.maxDepth(),context.callFrame(),context.iterationPhases(),context.cardinalities());
			complete &= visitShapeContexts(shapes,occurrence,next,visitor);
		}
		return complete;
	}
	public static boolean transparent(Hop hop) {
		if(hop instanceof FunctionOp function) return function.getFunctionType() == FunctionOp.FunctionType.DML;
		return hop instanceof DataOp data && (data.getOp() == OpOpData.TRANSIENTREAD
			|| data.getOp() == OpOpData.TRANSIENTWRITE || data.getOp() == OpOpData.FUNCTIONOUTPUT);
	}
}
