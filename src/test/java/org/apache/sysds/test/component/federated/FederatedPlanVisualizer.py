# -------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# -------------------------------------------------------------

import sys
import re
import networkx as nx
import matplotlib.pyplot as plt
import os
import argparse

try:
    import pygraphviz
    from networkx.drawing.nx_agraph import graphviz_layout
    HAS_PYGRAPHVIZ = True
except ImportError:
    HAS_PYGRAPHVIZ = False
    print("[WARNING] pygraphviz not found. Please use 'pip install pygraphviz'.\n"
          "      If installation fails, alternative layouts like spring_layout will be used.")


# Operation and variable abbreviation dictionary
OPERATION_ABBR = {
    # General operators
    "TRead": "TR",
    "TWrite": "TW",
    "Aggregate": "Agg",
    "AggregateUnary": "AgU",
    "Binary": "Bin",
    "Unary": "Un",
    "Reorg": "Rog",
    "MatrixIndexing": "MIdx",
    "Transpose": "Trp",
    "Reshape": "Rshp",
    "Literal": "Lit",
    
    # Federation related operators
    "transferMatrix": "tMat",
    "transferMatrixFromRemoteToLocal": "t2Loc",
    "transferMatrixFromLocalToRemote": "t2Rem",
    "federated": "fed",
    "federatedOutput": "fOut",
    "localOutput": "lOut",
    "noderef": "nRef",
    
    # KMeans algorithm related operators
    "kmeans": "KM",
    "kmeansPredict": "KMP",
    "m_kmeans": "mKM",
    
    # Other operations
    "append": "app",
    "cbind": "cb",
    "rbind": "rb",
    "matrix": "mat",
    "conv2d": "c2d",
    "maxpool": "mxp",
    "convolution": "cnv",
    "pooling": "pool",
    "QuantizeMatrix": "QMat",
    "DeQuantizeMatrix": "DQMat"
}

# Variable abbreviation dictionary (commonly used variable names)
VARIABLE_ABBR = {
    "matrix": "Mat",
    "weight": "Wei",
    "input": "In",
    "output": "Out",
    "image": "Img",
    "prediction": "Pred",
    "target": "Tgt",
    "gradient": "Grad",
    "activation": "Act",
    "feature": "Feat",
    "label": "Lbl",
    "parameter": "Param",
    "temp": "Tmp",
    "temporary": "Tmp",
    "intermediate": "Imd",
    "result": "Res"
}

def parse_line(line: str, debug: bool = False):
    # Print original line
    if debug:
        print(f"Original line: {line}")
    
    # Skip empty lines or info lines like 'Additional Cost:'
    if not line or line.startswith("Additional Cost:"):
        return None
    
    # Parse GetFederatedType log entries
    if line.startswith("[GetFederatedType]"):
        return parse_get_federated_type_line(line, debug)
    
    # Parse existing federated plan log entries
    return parse_federated_plan_line(line, debug)


def parse_get_federated_type_line(line: str, debug: bool = False):
    """Parse GetFederatedType log entries and extract FType information"""
    if debug:
        print(f"Parsing GetFederatedType line: {line}")
    
    # Extract components from GetFederatedType log
    hop_name_match = re.search(r'HopName:\s*([^|]+)', line)
    hop_id_match = re.search(r'HopID:\s*(\d+)', line)
    operation_type_match = re.search(r'OperationType:\s*([^|]+)', line)
    op_code_match = re.search(r'OpCode:\s*([^|]+)', line)
    input_ftypes_match = re.search(r'InputFTypes:\s*(\[[^\]]*\])', line)
    return_ftype_match = re.search(r'ReturnFType:\s*([^|]+)', line)
    reason_match = re.search(r'Reason:\s*(.+)$', line)
    
    if not hop_id_match:
        if debug:
            print(f"  > HopID not found in GetFederatedType line")
        return None
    
    hop_name = hop_name_match.group(1).strip() if hop_name_match else ""
    hop_id = hop_id_match.group(1).strip()
    operation_type = operation_type_match.group(1).strip() if operation_type_match else ""
    op_code = op_code_match.group(1).strip() if op_code_match else ""
    input_ftypes_str = input_ftypes_match.group(1).strip() if input_ftypes_match else "[]"
    return_ftype = return_ftype_match.group(1).strip() if return_ftype_match else "null"
    reason = reason_match.group(1).strip() if reason_match else ""
    
    # Parse input FTypes array
    input_ftypes = []
    if input_ftypes_str and input_ftypes_str != "[]":
        # Remove brackets and split by comma
        ftypes_content = input_ftypes_str[1:-1]  # Remove [ and ]
        if ftypes_content.strip():
            input_ftypes = [ft.strip() for ft in ftypes_content.split(',')]
    
    if debug:
        print(f"  > Parsed GetFederatedType: HopID={hop_id}, HopName={hop_name}")
        print(f"    OperationType={operation_type}, OpCode={op_code}")
        print(f"    InputFTypes={input_ftypes}, ReturnFType={return_ftype}")
        print(f"    Reason={reason}")
    
    return {
        'type': 'GetFederatedType',
        'node_id': hop_id,
        'hop_name': hop_name,
        'operation_type': operation_type,
        'op_code': op_code,
        'input_ftypes': input_ftypes,
        'return_ftype': return_ftype,
        'reason': reason
    }


def parse_federated_plan_line(line: str, debug: bool = False):
    """Parse federated plan log entries (existing functionality)"""
    
    # Check if this is the new format (starts with [HopID]:)
    if line.startswith("[HopID]:"):
        # Parse new format directly: [HopID]: X, [Name]: Y, [OutputType/FOutType]: Z, [ChildHopIDs]: (id1, id2), [ParentHopIDs]: (parent1, parent2)
        number_pattern = r'[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?'
        
        # Extract HopID (node_id)
        match_hop_id = re.search(r'\[HopID\]:\s*([^,]+)', line)
        if not match_hop_id:
            if debug:
                print(f"  > HopID not found: {line}")
            return None
        node_id = match_hop_id.group(1).strip()
        if debug:
            print(f"  > Node ID: {node_id}")

        # Extract Name (operation)
        operation = ""
        match_name = re.search(r'\[Name\]:\s*([^,]+)', line)
        if match_name:
            operation = match_name.group(1).strip()
            if debug:
                print(f"  > Name/operation: {operation}")

        # Extract ExecType (if available)
        exec_type = ""
        match_exec_type = re.search(r'\[ExecType\]:\s*([^,]+)', line)
        if match_exec_type:
            exec_type = match_exec_type.group(1).strip()
            if debug:
                print(f"  > ExecType: {exec_type}")

        # Extract FOutType (kind)
        kind = ""
        output_type = ""
        match_output_type = re.search(r'\[OutputType\]:\s*([^,]+)', line)
        match_fout = re.search(r'\[FOutType\]:\s*([^,]+)', line)
        if match_output_type:
            kind = match_output_type.group(1).strip()
            output_type = kind
        elif match_fout:
            kind = match_fout.group(1).strip()
            output_type = kind
        if debug:
            print(f"  > FOutType/kind: {kind}")

        # Extract FType (if available)
        ftype = ""
        match_ftype = re.search(r'\[FType\]:\s*([^,]+)', line)
        if match_ftype:
            ftype = match_ftype.group(1).strip()
            if debug:
                print(f"  > FType: {ftype}")

        # Extract ChildHopIDs
        child_ids = []
        match_child_ids = re.search(r'\[ChildHopIDs\]:\s*\(([^)]*)\)', line)
        if match_child_ids:
            children_str = match_child_ids.group(1)
            if children_str.strip():
                child_ids = [c.strip() for c in children_str.split(',') if c.strip()]
            if debug:
                print(f"  > ChildHopIDs: {child_ids}")

        # Extract ParentHopIDs (not used for graph building but kept for reference)
        parent_ids = []
        match_parent_ids = re.search(r'\[ParentHopIDs\]:\s*\(([^)]*)\)', line)
        if match_parent_ids:
            parents_str = match_parent_ids.group(1)
            if parents_str.strip():
                parent_ids = [p.strip() for p in parents_str.split(',') if p.strip()]
            if debug:
                print(f"  > ParentHopIDs: {parent_ids}")

        # Extract cost information from CostInfo (if present)
        total = ""
        self_cost = ""
        weight = ""
        network_cost = ""
        match_cost_info = re.search(r'\[CostInfo\]:\s*\{([^}]+)\}', line)
        if match_cost_info:
            cost_content = match_cost_info.group(1)
            m_total = re.search(rf'TotalCost:\s*({number_pattern})', cost_content)
            m_self = re.search(rf'SelfCost:\s*({number_pattern})', cost_content)
            m_network = re.search(rf'NetworkCost:\s*({number_pattern})', cost_content)
            m_weight = re.search(rf'ComputeWeight:\s*({number_pattern})', cost_content)
            if m_total:
                total = m_total.group(1)
            if m_self:
                self_cost = m_self.group(1)
            if m_network:
                network_cost = m_network.group(1)
            if m_weight:
                weight = m_weight.group(1)
            if debug:
                print(f"  > CostInfo - Total: {total}, Self: {self_cost}, Network: {network_cost}, Weight: {weight}")

        # Extract edge details from EdgeInfo (if present)
        edge_details = {}
        match_edge_info = re.search(r'\[EdgeInfo\]:\s*\{([^}]+)\}', line)
        if match_edge_info:
            edges_str = match_edge_info.group(1)
            if debug:
                print(f"  > EdgeInfo content: {edges_str}")
            
            # Parse edge items: Edge(ID:X, ForwardingCost:Y, CumulativeCost:Z, ForwardingWeight:W, TotalForwarding:T)
            edge_items = re.findall(r'Edge\([^)]+\)', edges_str)
            
            for item in edge_items:
                if debug:
                    print(f"  > Edge item to parse: '{item}'")
                
                # Parse edge info
                id_match = re.search(r'ID:(\d+)', item)
                forwarding_cost_match = re.search(r'ForwardingCost:([XO])', item)
                cumulative_cost_match = re.search(r'CumulativeCost:([\d\.]+)', item)
                forward_weight_match = re.search(r'ForwardingWeight:([\d\.]+)', item)
                total_forwarding_match = re.search(r'TotalForwarding:([\d\.]+)', item)
                
                if id_match:
                    source_id = id_match.group(1)
                    is_forwarding = forwarding_cost_match and forwarding_cost_match.group(1) == 'O'
                    cumulative_cost = cumulative_cost_match.group(1) if cumulative_cost_match else None
                    forward_cost = total_forwarding_match.group(1) if total_forwarding_match else "0.0"
                    forward_weight = forward_weight_match.group(1) if forward_weight_match else "1.0"
                    
                    if debug:
                        print(f"  > Parse edge details: source={source_id}, forwarding={'O' if is_forwarding else 'X'}, cumulative={cumulative_cost}, cost={forward_cost}, weight={forward_weight}")
                    
                    edge_details[source_id] = {
                        'is_forwarding': is_forwarding,
                        'cumulative_cost': cumulative_cost,
                        'forward_cost': forward_cost,
                        'forward_weight': forward_weight
                    }

        if debug:
            print(f"  > Edge details: {edge_details}")
            print("-------------------------------------")

        return {
            'node_id': node_id,
            'operation': operation,
            'kind': kind,
            'output_type': output_type,
            'exec_type': exec_type,
            'ftype': ftype,
            'total': total,
            'self_cost': self_cost,
            'network_cost': network_cost,
            'weight': weight,
            'child_ids': child_ids,
            'parent_ids': parent_ids,
            'edge_details': edge_details
        }
    
    else:
        # Parse old format with (ID) prefix
        # 1) Extract node ID
        match_id = re.match(r'^\((R|\d+)\)', line)
        if not match_id:
            if debug:
                print(f"  > Node ID not found: {line}")
            return None
        node_id = match_id.group(1)
        if debug:
            print(f"  > Node ID: {node_id}")

        # 2) Remaining string after node id
        after_id = line[match_id.end():].strip()
        if debug:
            print(f"  > String after ID: {after_id}")

        # Parse old format
        # hop name (label): string before the first "["
        match_label = re.search(r'^(.*?)\s*\[', after_id)
        if match_label:
            operation = match_label.group(1).strip()
        else:
            operation = after_id.strip()
        if debug:
            print(f"  > Hop name/operation: {operation}")

        # kind: content inside the first brackets (e.g., "FOUT" or "LOUT")
        match_bracket = re.search(r'\[([^\]]+)\]', after_id)
        if match_bracket:
            kind = match_bracket.group(1).strip()
        else:
            kind = ""
        if debug:
            print(f"  > Kind: {kind}")

        # total, self, weight: extract from content inside curly braces {}
        total = ""
        self_cost = ""
        weight = ""
        match_curly = re.search(r'\{([^}]+)\}', line)
        if match_curly:
            curly_content = match_curly.group(1)
            m_total = re.search(r'Total:\s*([\d\.]+)', curly_content)
            m_self = re.search(r'Self:\s*([\d\.]+)', curly_content)
            m_weight = re.search(r'Weight:\s*([\d\.]+)', curly_content)
            if m_total:
                total = m_total.group(1)
            if m_self:
                self_cost = m_self.group(1)
            if m_weight:
                weight = m_weight.group(1)
        if debug:
            print(f"  > Total: {total}, Self: {self_cost}, Weight: {weight}")

        # Extract reference nodes (children): numbers inside the first parentheses after kind (multiple possible)
        child_ids = []
        # Find parentheses after the first [
        match_children = re.search(r'\[[^\]]+\]\s*\(([^)]+)\)', after_id)
        if match_children:
            children_str = match_children.group(1)
            if debug:
                print(f"  > Child node string: {children_str}")
            # Extract comma-separated IDs
            child_ids = [c.strip() for c in children_str.split(',') if c.strip()]
        if debug:
            print(f"  > Child Node IDs: {child_ids}")
        
        # Edge details: extract from [Edges]{...}
        edge_details = {}
        match_edges = re.search(r'\[Edges\]\{(.*?)(?:\}|$)', line)
        if match_edges:
            edges_str = match_edges.group(1)
            if debug:
                print(f"  > [Edges] content: {edges_str}")
            
            # Separate each edge info by parentheses
            edge_items = re.findall(r'\(ID:[^)]+\)', edges_str)
            
            for item in edge_items:
                if debug:
                    print(f"  > Part to parse: '{item}'")
                
                # Parse edge info: (ID:51, X, C:401810.0, F:0.0, FW:500.0)
                id_match = re.search(r'ID:(\d+)', item)
                xo_match = re.search(r',\s*([XO])', item)
                cumulative_match = re.search(r'C:([\d\.]+)', item)
                forward_match = re.search(r'F:([\d\.]+)', item)
                weight_match = re.search(r'FW:([\d\.]+)', item)
                
                if id_match:
                    source_id = id_match.group(1)
                    is_forwarding = xo_match and xo_match.group(1) == 'O'
                    cumulative_cost = cumulative_match.group(1) if cumulative_match else None
                    forward_cost = forward_match.group(1) if forward_match else "0.0"
                    forward_weight = weight_match.group(1) if weight_match else "1.0"
                    
                    if debug:
                        print(f"  > Parse edge details: source={source_id}, forwarding={'O' if is_forwarding else 'X'}, cumulative={cumulative_cost}, cost={forward_cost}, weight={forward_weight}")
                    
                    edge_details[source_id] = {
                        'is_forwarding': is_forwarding,
                        'cumulative_cost': cumulative_cost,
                        'forward_cost': forward_cost,
                        'forward_weight': forward_weight
                    }

        if debug:
            print(f"  > Edge details: {edge_details}")
            print("-------------------------------------")

        return {
            'node_id': node_id,
            'operation': operation,
            'kind': kind,
            'total': total,
            'self_cost': self_cost,
            'weight': weight,
            'child_ids': child_ids,
            'edge_details': edge_details
        }


def build_dag_from_file(filename: str, debug: bool = False):
    G = nx.DiGraph()
    get_federated_type_info = {}  # Store GetFederatedType information by HopID
    if debug:
        print(f"\n[INFO] Building graph from file '{filename}'.")
    
    line_count = 0
    parsed_count = 0
    
    with open(filename, 'r', encoding='utf-8') as f:
        for line in f:
            line_count += 1
            line = line.strip()
            if not line:
                continue

            info = parse_line(line, debug=debug)
            if not info:
                continue
                
            # Handle GetFederatedType log entries (store for later use)
            if info.get('type') == 'GetFederatedType':
                get_federated_type_info[info['node_id']] = info
                continue
                
            parsed_count += 1
            node_id = info['node_id']
            operation = info.get('operation', '')
            kind = info.get('kind', '') or info.get('output_type', '')
            total = info.get('total', '')
            self_cost = info.get('self_cost', '')
            weight = info.get('weight', '')
            network_cost = info.get('network_cost', '')
            exec_type = info.get('exec_type', '')
            ftype = info.get('ftype', '')
            parent_ids = info.get('parent_ids', [])
            child_ids = info.get('child_ids', [])
            edge_details = info.get('edge_details', {})

            if debug:
                print(f"Adding node: {node_id}, label: {operation}, kind: {kind}")
            G.add_node(
                node_id,
                label=operation,
                kind=kind,
                total=total,
                self_cost=self_cost,
                weight=weight,
                network_cost=network_cost,
                exec_type=exec_type,
                ftype=ftype,
                parent_ids=parent_ids
            )

            # 1. First create basic edges with child IDs in ()
            for child_id in child_ids:
                # Create child node if it doesn't exist
                if child_id not in G:
                    if debug:
                        print(f"  > Creating missing child node: {child_id}")
                    G.add_node(child_id, label=child_id, kind="", total="", self_cost="", weight="")
                
                # Add edge from child node to current node (child -> parent)
                # For new format without EdgeInfo, treat as discovered edges
                if debug:
                    print(f"  > Adding basic edge: {child_id} -> {node_id} (basic edge)")
                G.add_edge(child_id, node_id, 
                          is_forwarding=False,
                          forward_cost="0.0",  # Default cost for basic edges
                          forward_weight="1.0",  # Default weight for basic edges
                          is_discovered=True)  # Mark as discovered for new format

            # 1b. Add edges using parent IDs when present (new format sometimes only lists parents)
            for parent_id in parent_ids:
                if parent_id not in G:
                    if debug:
                        print(f"  > Creating missing parent node: {parent_id}")
                    G.add_node(parent_id, label=parent_id, kind="", total="", self_cost="", weight="")

                # Avoid duplicate edges if already added via child_ids
                if not G.has_edge(node_id, parent_id):
                    if debug:
                        print(f"  > Adding parent edge: {node_id} -> {parent_id} (from ParentHopIDs)")
                    G.add_edge(node_id, parent_id,
                               is_forwarding=False,
                               forward_cost="0.0",
                               forward_weight="1.0",
                               is_discovered=True)
            
            # 2. Update edge attributes with [Edges] info
            for source_id, edge_data in edge_details.items():
                # Create source node if it doesn't exist
                if source_id not in G:
                    if debug:
                        print(f"  > Creating missing source node: {source_id}")
                    G.add_node(source_id, label=source_id, kind="", total="", self_cost="", weight="")
                
                # Create edge if it doesn't exist, otherwise just update attributes
                if not G.has_edge(source_id, node_id):
                    # Set edge attributes
                    edge_attrs = {
                        'is_forwarding': edge_data['is_forwarding'],
                        'forward_cost': edge_data['forward_cost'],
                        'forward_weight': edge_data['forward_weight'],
                        'is_discovered': True  # Edge discovered in [Edges]
                    }
                    
                    # Add cumulative cost if available
                    if 'cumulative_cost' in edge_data and edge_data['cumulative_cost'] is not None:
                        edge_attrs['cumulative_cost'] = edge_data['cumulative_cost']
                        
                    if debug:
                        print(f"  > Adding edge: {source_id} -> {node_id}, Forwarding: {edge_data['is_forwarding']}, Cost: {edge_data['forward_cost']}, Weight: {edge_data['forward_weight']}, Cumulative: {edge_data['cumulative_cost']}")
                    G.add_edge(source_id, node_id, **edge_attrs)
                else:
                    if debug:
                        print(f"  > Updating edge attributes: {source_id} -> {node_id}, Forwarding: {edge_data['is_forwarding']}, Cost: {edge_data['forward_cost']}, Weight: {edge_data['forward_weight']}, Cumulative: {edge_data['cumulative_cost']}")
                    G[source_id][node_id]['is_forwarding'] = edge_data['is_forwarding']
                    G[source_id][node_id]['forward_cost'] = edge_data['forward_cost']
                    G[source_id][node_id]['forward_weight'] = edge_data['forward_weight']
                    G[source_id][node_id]['is_discovered'] = True  # Edge discovered in Edges
                    
                    # Add cumulative cost if available
                    if 'cumulative_cost' in edge_data and edge_data['cumulative_cost'] is not None:
                        G[source_id][node_id]['cumulative_cost'] = edge_data['cumulative_cost']

    if debug:
        print(f"\n[INFO] Parsed {parsed_count} nodes out of {line_count} total lines.")
        print(f"[INFO] Graph info: {len(G.nodes())} nodes, {len(G.edges())} edges\n")
        
        print("--- Node Information ---")
        for node, data in G.nodes(data=True):
            print(f"Node {node}: {data}")
        
        print("\n--- Edge Information ---")
        for u, v, data in G.edges(data=True):
            print(f"Edge {u} -> {v}: {data}")
    
    return G, get_federated_type_info


def analyze_hop_costs(G):
    """Analyze hop-level computing costs and calculate ratios"""
    total_compute_cost = 0.0
    hop_costs = {}
    
    # Calculate total computing cost from all hops
    for node, data in G.nodes(data=True):
        if node == 'ROOT':
            continue
        self_cost = data.get('self_cost', '0')
        try:
            cost = float(self_cost) if self_cost else 0.0
            hop_costs[node] = cost
            total_compute_cost += cost
        except (ValueError, TypeError):
            hop_costs[node] = 0.0
    
    # Calculate ratios
    hop_ratios = {}
    for hop, cost in hop_costs.items():
        ratio = (cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
        hop_ratios[hop] = ratio
    
    return hop_costs, hop_ratios, total_compute_cost


def analyze_edge_costs(G):
    """Analyze edge-level network costs and calculate ratios"""
    total_network_cost = 0.0
    edge_costs = {}
    
    # Calculate total network cost from all edges
    for u, v, data in G.edges(data=True):
        if u == 'ROOT' or v == 'ROOT':
            continue
        forward_cost = data.get('forward_cost', '0')
        try:
            cost = float(forward_cost) if forward_cost and forward_cost != '0.0' else 0.0
            edge_costs[(u, v)] = cost
            total_network_cost += cost
        except (ValueError, TypeError):
            edge_costs[(u, v)] = 0.0
    
    # Calculate ratios
    edge_ratios = {}
    for edge, cost in edge_costs.items():
        ratio = (cost / total_network_cost * 100) if total_network_cost > 0 else 0.0
        edge_ratios[edge] = ratio
    
    return edge_costs, edge_ratios, total_network_cost


def analyze_fout_hops(G):
    """Analyze FOUT hops computing costs"""
    fout_total_cost = 0.0
    fout_hops = {}
    
    for node, data in G.nodes(data=True):
        if node == 'ROOT':
            continue
        kind = data.get('kind', '').upper()
        if kind == 'FOUT':
            self_cost = data.get('self_cost', '0')
            try:
                cost = float(self_cost) if self_cost else 0.0
                fout_hops[node] = cost
                fout_total_cost += cost
            except (ValueError, TypeError):
                fout_hops[node] = 0.0
    
    return fout_hops, fout_total_cost


def analyze_lout_hops(G):
    """Analyze LOUT hops computing costs"""
    lout_total_cost = 0.0
    lout_hops = {}
    
    for node, data in G.nodes(data=True):
        if node == 'ROOT':
            continue
        kind = data.get('kind', '').upper()
        if kind == 'LOUT':
            self_cost = data.get('self_cost', '0')
            try:
                cost = float(self_cost) if self_cost else 0.0
                lout_hops[node] = cost
                lout_total_cost += cost
            except (ValueError, TypeError):
                lout_hops[node] = 0.0
    
    return lout_hops, lout_total_cost


def collect_fed_parents(G):
    """Find all Fed nodes and collect all their parent nodes recursively"""
    fed_parents = set()
    
    # Find all Fed nodes
    fed_nodes = []
    for node in G.nodes():
        label = G.nodes[node].get('label', '').lower()
        if label.startswith('fed '):
            fed_nodes.append(node)
            fed_parents.add(node)  # Fed nodes themselves are also included
    
    # For each Fed node, recursively collect all parent nodes
    def collect_parents_recursive(node, visited):
        if node in visited:
            return
        visited.add(node)
        
        # Get all nodes that this node points to (parent nodes in the data flow)
        for parent in G.successors(node):
            fed_parents.add(parent)
            collect_parents_recursive(parent, visited)
    
    for fed_node in fed_nodes:
        collect_parents_recursive(fed_node, set())
    
    return fed_parents


def analyze_lout_to_fout_transitions(G, fout_to_lout_edges):
    """Analyze LOUT to FOUT transition hops and edges (all transitions)"""
    transition_analysis = {
        'hops': {},
        'edges': {},
        'total_hop_cost': 0.0,
        'total_edge_cost': 0.0
    }
    
    # Analyze transition edges
    for u, v in fout_to_lout_edges:
        edge_data = G.get_edge_data(u, v)
        if edge_data:
            forward_cost = edge_data.get('forward_cost', '0')
            try:
                cost = float(forward_cost) if forward_cost else 0.0
                transition_analysis['edges'][(u, v)] = cost
                transition_analysis['total_edge_cost'] += cost
            except (ValueError, TypeError):
                transition_analysis['edges'][(u, v)] = 0.0
    
    # Analyze transition hops (both source and target of transition edges)
    transition_hops = set()
    for u, v in fout_to_lout_edges:
        transition_hops.add(u)
        transition_hops.add(v)
    
    for hop in transition_hops:
        hop_data = G.nodes[hop]
        self_cost = hop_data.get('self_cost', '0')
        try:
            cost = float(self_cost) if self_cost else 0.0
            transition_analysis['hops'][hop] = cost
            transition_analysis['total_hop_cost'] += cost
        except (ValueError, TypeError):
            transition_analysis['hops'][hop] = 0.0
    
    return transition_analysis


def analyze_fout_to_lout_transitions(G):
    """Analyze and print detailed FOUT to LOUT transition information"""
    fout_to_lout_edges = []
    
    # Find FOUT to LOUT transitions
    for u, v, d in G.edges(data=True):
        if v == 'ROOT' or u == 'ROOT':
            continue
        if 'is_discovered' in d and d['is_discovered']:
            u_kind = G.nodes[u].get('kind', '').upper()
            v_kind = G.nodes[v].get('kind', '').upper()
            
            if u_kind == 'FOUT' and v_kind == 'LOUT':
                fout_to_lout_edges.append((u, v))
    
    if not fout_to_lout_edges:
        return
    
    print(f"\n[INFO] FOUT to LOUT transition edges found ({len(fout_to_lout_edges)} edges):")
    print("="*80)
    
    for i, (source_id, target_id) in enumerate(fout_to_lout_edges, 1):
        # Get source node info
        source_data = G.nodes[source_id]
        source_label = source_data.get('label', source_id)
        source_total_cost = source_data.get('total', '0')
        source_self_cost = source_data.get('self_cost', '0')
        source_weight = source_data.get('weight', '1.0')
        
        # Get target node info  
        target_data = G.nodes[target_id]
        target_label = target_data.get('label', target_id)
        target_total_cost = target_data.get('total', '0')
        target_self_cost = target_data.get('self_cost', '0')
        target_weight = target_data.get('weight', '1.0')
        
        # Get edge info
        edge_data = G.get_edge_data(source_id, target_id)
        forward_cost = edge_data.get('forward_cost', '0.0') if edge_data else '0.0'
        forward_weight = edge_data.get('forward_weight', '1.0') if edge_data else '1.0'
        cumulative_cost = edge_data.get('cumulative_cost', source_total_cost) if edge_data else source_total_cost
        is_forwarding = edge_data.get('is_forwarding', False) if edge_data else False
        
        # Get children info
        source_children = []
        for child, _, _ in G.in_edges(source_id, data=True):
            if child != 'ROOT':
                source_children.append(child)
        
        target_children = []
        for child, _, _ in G.in_edges(target_id, data=True):
            if child != 'ROOT':
                target_children.append(child)
        
        # Format children list
        source_children_str = str(source_children[:5]) + (f"... (Total: {len(source_children)})" if len(source_children) > 5 else f" (Total: {len(source_children)})")
        target_children_str = str(target_children[:5]) + (f"... (Total: {len(target_children)})" if len(target_children) > 5 else f" (Total: {len(target_children)})")
        
        print(f"  {i}. Edge: {source_id} -> {target_id}")
        print(f"     Source: [FOUT] {source_label} (HopID: {source_id})")
        print(f"     Target: [LOUT] {target_label} (HopID: {target_id})")
        print(f"     Source Children: {source_children_str}")
        print(f"     Target Children: {target_children_str}")
        print(f"     Source Cost: Total={source_total_cost}, Self={source_self_cost}, Weight={source_weight}")
        print(f"     Target Cost: Total={target_total_cost}, Self={target_self_cost}, Weight={target_weight}")
        print(f"     Edge Info: ForwardCost={forward_cost}, ForwardWeight={forward_weight}, Forwarding={'Yes' if is_forwarding else 'No'}, CumulativeCost={cumulative_cost}")
        print(f"     Analysis: {'Data read from federated source -> Local processing' if i == 1 else 'FOUT to LOUT transition - potential data transfer point'}")
        print("     " + "-"*70)
    
    print("="*80)
    print(f"[INFO] Total FOUT to LOUT transitions: {len(fout_to_lout_edges)}")
    print("[INFO] These edges represent data transfer from federated (FOUT) to local (LOUT) operations")


def export_cost_analysis_to_csv(G, fout_to_lout_edges, output_dir="visualization_output"):
    """Export cost analysis data to CSV files"""
    import csv
    
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # 1. Hop-level analysis
    hop_costs, hop_ratios, total_compute_cost = analyze_hop_costs(G)
    
    # 2. Edge-level analysis
    edge_costs, edge_ratios, total_network_cost = analyze_edge_costs(G)
    
    # 3. FOUT hops analysis
    fout_hops, fout_total_cost = analyze_fout_hops(G)
    
    # 4. LOUT hops analysis
    lout_hops, lout_total_cost = analyze_lout_hops(G)
    
    # Export summary statistics
    summary_file = os.path.join(output_dir, "cost_analysis_summary.csv")
    summary_file = get_unique_filename(summary_file)
    with open(summary_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(["Metric", "Value", "Percentage"])
        writer.writerow(["Total Computing Cost", f"{total_compute_cost:.2f}", "100.00%"])
        writer.writerow(["Total Network Cost", f"{total_network_cost:.2f}", "100.00%"])
        
        # Calculate total hops excluding ROOT
        total_hops = len([n for n in G.nodes() if n != 'ROOT'])
        
        # FOUT/LOUT cost and count ratios
        fout_ratio = (fout_total_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
        lout_ratio = (lout_total_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
        fout_count_ratio = (len(fout_hops) / total_hops * 100) if total_hops > 0 else 0.0
        lout_count_ratio = (len(lout_hops) / total_hops * 100) if total_hops > 0 else 0.0
        
        writer.writerow(["FOUT Hops Total Computing Cost", f"{fout_total_cost:.2f}", f"{fout_ratio:.2f}%"])
        writer.writerow(["LOUT Hops Total Computing Cost", f"{lout_total_cost:.2f}", f"{lout_ratio:.2f}%"])
        writer.writerow(["Number of FOUT Hops", str(len(fout_hops)), f"{fout_count_ratio:.2f}%"])
        writer.writerow(["Number of LOUT Hops", str(len(lout_hops)), f"{lout_count_ratio:.2f}%"])
        writer.writerow(["Total Hops (excluding ROOT)", str(total_hops), "100.00%"])
        
        # Fed parents analysis
        fed_parent_nodes = collect_fed_parents(G)
        if fed_parent_nodes:
            # Count FOUT/LOUT hops in Fed parents
            fed_parent_fout_hops = {}
            fed_parent_lout_hops = {}
            fed_parent_fout_cost = 0.0
            fed_parent_lout_cost = 0.0
            
            for node in fed_parent_nodes:
                if node == 'ROOT':
                    continue
                kind = G.nodes[node].get('kind', '').upper()
                self_cost = G.nodes[node].get('self_cost', '0')
                try:
                    cost = float(self_cost) if self_cost else 0.0
                    if kind == 'FOUT':
                        fed_parent_fout_hops[node] = cost
                        fed_parent_fout_cost += cost
                    elif kind == 'LOUT':
                        fed_parent_lout_hops[node] = cost
                        fed_parent_lout_cost += cost
                except (ValueError, TypeError):
                    pass
            
            # Calculate ratios for Fed parents
            fed_parent_total_cost = fed_parent_fout_cost + fed_parent_lout_cost
            fed_parent_fout_cost_ratio = (fed_parent_fout_cost / fed_parent_total_cost * 100) if fed_parent_total_cost > 0 else 0.0
            fed_parent_lout_cost_ratio = (fed_parent_lout_cost / fed_parent_total_cost * 100) if fed_parent_total_cost > 0 else 0.0
            
            fed_parent_total_hops = len(fed_parent_fout_hops) + len(fed_parent_lout_hops)
            fed_parent_fout_count_ratio = (len(fed_parent_fout_hops) / fed_parent_total_hops * 100) if fed_parent_total_hops > 0 else 0.0
            fed_parent_lout_count_ratio = (len(fed_parent_lout_hops) / fed_parent_total_hops * 100) if fed_parent_total_hops > 0 else 0.0
            
            writer.writerow([])  # Empty row for separation
            writer.writerow(["Fed Parents Analysis", "", ""])
            writer.writerow(["Total Fed Parent Nodes", str(len(fed_parent_nodes)), ""])
            writer.writerow(["Fed Parent FOUT Hops", str(len(fed_parent_fout_hops)), f"{fed_parent_fout_count_ratio:.2f}%"])
            writer.writerow(["Fed Parent LOUT Hops", str(len(fed_parent_lout_hops)), f"{fed_parent_lout_count_ratio:.2f}%"])
            writer.writerow(["Fed Parent FOUT Cost", f"{fed_parent_fout_cost:.2f}", f"{fed_parent_fout_cost_ratio:.2f}%"])
            writer.writerow(["Fed Parent LOUT Cost", f"{fed_parent_lout_cost:.2f}", f"{fed_parent_lout_cost_ratio:.2f}%"])
    
    # Export top expensive hops
    hops_file = os.path.join(output_dir, "top_expensive_hops.csv")
    hops_file = get_unique_filename(hops_file)
    with open(hops_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(["Rank", "HopID", "Type", "Name", "Cost", "Percentage"])
        sorted_hops = sorted(hop_ratios.items(), key=lambda x: x[1], reverse=True)[:10]
        for i, (hop, ratio) in enumerate(sorted_hops, 1):
            hop_data = G.nodes[hop]
            hop_name = hop_data.get('label', hop)
            hop_kind = hop_data.get('kind', 'N/A')
            writer.writerow([i, hop, hop_kind, hop_name, f"{hop_costs[hop]:.2f}", f"{ratio:.2f}%"])
    
    # Export top expensive edges
    edges_file = os.path.join(output_dir, "top_expensive_edges.csv")
    edges_file = get_unique_filename(edges_file)
    with open(edges_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(["Rank", "Source_HopID", "Source_Type", "Source_Name", "Target_HopID", "Target_Type", "Target_Name", "Cost", "Percentage"])
        if total_network_cost > 0:
            sorted_edges = sorted(edge_ratios.items(), key=lambda x: x[1], reverse=True)[:10]
            for i, (edge, ratio) in enumerate(sorted_edges, 1):
                u, v = edge
                u_data = G.nodes[u]
                v_data = G.nodes[v]
                u_name = u_data.get('label', u)
                u_kind = u_data.get('kind', 'N/A')
                v_name = v_data.get('label', v)
                v_kind = v_data.get('kind', 'N/A')
                writer.writerow([i, u, u_kind, u_name, v, v_kind, v_name, f"{edge_costs[edge]:.2f}", f"{ratio:.2f}%"])
    
    # Export FOUT to LOUT transition analysis
    transition_file = os.path.join(output_dir, "fout_to_lout_transitions.csv")
    transition_file = get_unique_filename(transition_file)
    with open(transition_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(["Transition_ID", "Source_HopID", "Source_Type", "Source_Name", "Source_Total_Cost", "Source_Self_Cost", "Source_Weight", 
                        "Target_HopID", "Target_Type", "Target_Name", "Target_Total_Cost", "Target_Self_Cost", "Target_Weight",
                        "Edge_Forward_Cost", "Edge_Forward_Weight", "Edge_Cumulative_Cost", "Is_Forwarding",
                        "Source_Children_Count", "Target_Children_Count", "Analysis_Description"])
        
        if fout_to_lout_edges:
            for i, (source_id, target_id) in enumerate(fout_to_lout_edges, 1):
                # Get source node info
                source_data = G.nodes[source_id]
                source_label = source_data.get('label', source_id)
                source_total_cost = source_data.get('total', '0')
                source_self_cost = source_data.get('self_cost', '0')
                source_weight = source_data.get('weight', '1.0')
                
                # Get target node info  
                target_data = G.nodes[target_id]
                target_label = target_data.get('label', target_id)
                target_total_cost = target_data.get('total', '0')
                target_self_cost = target_data.get('self_cost', '0')
                target_weight = target_data.get('weight', '1.0')
                
                # Get edge info
                edge_data = G.get_edge_data(source_id, target_id)
                forward_cost = edge_data.get('forward_cost', '0.0') if edge_data else '0.0'
                forward_weight = edge_data.get('forward_weight', '1.0') if edge_data else '1.0'
                cumulative_cost = edge_data.get('cumulative_cost', source_total_cost) if edge_data else source_total_cost
                is_forwarding = edge_data.get('is_forwarding', False) if edge_data else False
                
                # Get children count
                source_children_count = len([child for child, _, _ in G.in_edges(source_id, data=True) if child != 'ROOT'])
                target_children_count = len([child for child, _, _ in G.in_edges(target_id, data=True) if child != 'ROOT'])
                
                analysis_desc = 'Data read from federated source -> Local processing' if i == 1 else 'FOUT to LOUT transition - potential data transfer point'
                
                writer.writerow([i, source_id, 'FOUT', source_label, source_total_cost, source_self_cost, source_weight,
                               target_id, 'LOUT', target_label, target_total_cost, target_self_cost, target_weight,
                               forward_cost, forward_weight, cumulative_cost, 'Yes' if is_forwarding else 'No',
                               source_children_count, target_children_count, analysis_desc])
    
    print(f"\n[INFO] Cost analysis data exported to CSV files:")
    print(f"  - Summary: {summary_file}")
    print(f"  - Top Hops: {hops_file}")
    print(f"  - Top Edges: {edges_file}")
    print(f"  - FOUT to LOUT Transitions: {transition_file}")


def generate_cost_analysis_report(G, fout_to_lout_edges, get_federated_type_info, filename, output_dir="visualization_output"):
    """Generate comprehensive cost analysis report"""
    import io
    import sys
    
    # Capture all print output
    old_stdout = sys.stdout
    sys.stdout = captured_output = io.StringIO()
    
    # Get base filename without path
    base_filename = filename.split('/')[-1] if '/' in filename else filename
    
    print("\n" + "="*80)
    print(f"COMPREHENSIVE COST ANALYSIS REPORT - {base_filename}")
    print("="*80)
    
    # 1. Hop-level analysis
    hop_costs, hop_ratios, total_compute_cost = analyze_hop_costs(G)
    print(f"\n1. HOP-LEVEL COMPUTING COST ANALYSIS")
    print(f"   Total Computing Cost: {total_compute_cost:.2f}")
    print(f"   Top 10 Most Expensive Hops:")
    sorted_hops = sorted(hop_ratios.items(), key=lambda x: x[1], reverse=True)[:10]
    for i, (hop, ratio) in enumerate(sorted_hops, 1):
        hop_data = G.nodes[hop]
        hop_name = hop_data.get('label', hop)
        hop_kind = hop_data.get('kind', 'N/A')
        print(f"   {i:2d}. HopID: {hop} [{hop_kind}] {hop_name[:30]:<30} Cost: {hop_costs[hop]:>8.2f} ({ratio:>5.2f}%)")
    
    # 2. Edge-level analysis
    edge_costs, edge_ratios, total_network_cost = analyze_edge_costs(G)
    print(f"\n2. EDGE-LEVEL NETWORK COST ANALYSIS")
    print(f"   Total Network Cost: {total_network_cost:.2f}")
    if total_network_cost > 0:
        print(f"   Top 10 Most Expensive Edges:")
        sorted_edges = sorted(edge_ratios.items(), key=lambda x: x[1], reverse=True)[:10]
        for i, (edge, ratio) in enumerate(sorted_edges, 1):
            u, v = edge
            u_data = G.nodes[u]
            v_data = G.nodes[v]
            u_name = u_data.get('label', u)
            u_kind = u_data.get('kind', 'N/A')
            v_name = v_data.get('label', v)
            v_kind = v_data.get('kind', 'N/A')
            print(f"   {i:2d}. Edge: {u}[{u_kind}]{u_name} -> {v}[{v_kind}]{v_name} Cost: {edge_costs[edge]:>8.2f} ({ratio:>5.2f}%)")
    else:
        print("   No network costs found in edges.")
    
    # 3. FOUT hops analysis
    fout_hops, fout_total_cost = analyze_fout_hops(G)
    fout_ratio = (fout_total_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
    # Calculate total hops excluding ROOT
    total_hops = len([n for n in G.nodes() if n != 'ROOT'])
    fout_count_ratio = (len(fout_hops) / total_hops * 100) if total_hops > 0 else 0.0
    print(f"\n3. FOUT HOPS COMPUTING COST ANALYSIS")
    print(f"   FOUT Hops Total Cost: {fout_total_cost:.2f} ({fout_ratio:.2f}% of total)")
    print(f"   Number of FOUT Hops: {len(fout_hops)} ({fout_count_ratio:.2f}% of total hops)")
    
    # 4. LOUT hops analysis
    lout_hops, lout_total_cost = analyze_lout_hops(G)
    lout_ratio = (lout_total_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
    lout_count_ratio = (len(lout_hops) / total_hops * 100) if total_hops > 0 else 0.0
    print(f"\n4. LOUT HOPS COMPUTING COST ANALYSIS")
    print(f"   LOUT Hops Total Cost: {lout_total_cost:.2f} ({lout_ratio:.2f}% of total)")
    print(f"   Number of LOUT Hops: {len(lout_hops)} ({lout_count_ratio:.2f}% of total hops)")
    
    # 5. FOUT to LOUT TRANSITION ANALYSIS (Integrated)
    if not fout_to_lout_edges:
        print(f"\n5. FOUT to LOUT TRANSITION ANALYSIS")
        print(f"   No FOUT to LOUT transitions found.")
    else:
        transition_analysis = analyze_lout_to_fout_transitions(G, fout_to_lout_edges)
        transition_hop_ratio = (transition_analysis['total_hop_cost'] / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
        transition_edge_ratio = (transition_analysis['total_edge_cost'] / total_network_cost * 100) if total_network_cost > 0 else 0.0
        
        print(f"\n5. FOUT to LOUT TRANSITION ANALYSIS - {base_filename}")
        print(f"   FOUT to LOUT transition edges found: {len(fout_to_lout_edges)} edges")
        print("   " + "="*75)
        print(f"   Total Transition Hops Computing Cost: {transition_analysis['total_hop_cost']:.2f} ({transition_hop_ratio:.2f}% of total)")
        print(f"   Total Transition Edges Network Cost: {transition_analysis['total_edge_cost']:.2f} ({transition_edge_ratio:.2f}% of total)")
        print(f"   These edges represent data transfer from federated (FOUT) to local (LOUT) operations")
        print()
        
        # Detailed analysis for each transition
        for i, (source_id, target_id) in enumerate(fout_to_lout_edges, 1):
            # Get source node info
            source_data = G.nodes[source_id]
            source_label = source_data.get('label', source_id)
            source_total_cost = source_data.get('total', '0')
            source_self_cost = source_data.get('self_cost', '0')
            source_weight = source_data.get('weight', '1.0')
            
            # Get target node info  
            target_data = G.nodes[target_id]
            target_label = target_data.get('label', target_id)
            target_total_cost = target_data.get('total', '0')
            target_self_cost = target_data.get('self_cost', '0')
            target_weight = target_data.get('weight', '1.0')
            
            # Get edge info
            edge_data = G.get_edge_data(source_id, target_id)
            forward_cost = edge_data.get('forward_cost', '0.0') if edge_data else '0.0'
            forward_weight = edge_data.get('forward_weight', '1.0') if edge_data else '1.0'
            cumulative_cost = edge_data.get('cumulative_cost', source_total_cost) if edge_data else source_total_cost
            is_forwarding = edge_data.get('is_forwarding', False) if edge_data else False
            
            # Get cost analysis data
            u_cost = transition_analysis['hops'].get(source_id, 0.0)
            v_cost = transition_analysis['hops'].get(target_id, 0.0)
            edge_cost = transition_analysis['edges'].get((source_id, target_id), 0.0)
            
            u_ratio = (u_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
            v_ratio = (v_cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
            edge_ratio = (edge_cost / total_network_cost * 100) if total_network_cost > 0 else 0.0
            
            # Get FType information
            source_ftype_info = get_federated_type_info.get(source_id, {})
            target_ftype_info = get_federated_type_info.get(target_id, {})
            
            source_input_ftypes = source_ftype_info.get('input_ftypes', [])
            source_return_ftype = source_ftype_info.get('return_ftype', 'N/A')
            target_input_ftypes = target_ftype_info.get('input_ftypes', [])
            target_return_ftype = target_ftype_info.get('return_ftype', 'N/A')
            
            # Get children info
            source_children = []
            for child, _, _ in G.in_edges(source_id, data=True):
                if child != 'ROOT':
                    source_children.append(child)
            
            target_children = []
            for child, _, _ in G.in_edges(target_id, data=True):
                if child != 'ROOT':
                    target_children.append(child)
            
            # Format children list
            source_children_str = str(source_children[:5]) + (f"... (Total: {len(source_children)})" if len(source_children) > 5 else f" (Total: {len(source_children)})")
            target_children_str = str(target_children[:5]) + (f"... (Total: {len(target_children)})" if len(target_children) > 5 else f" (Total: {len(target_children)})")
            
            print(f"   {i}. Edge: {source_id} -> {target_id}")
            print(f"      Source: [FOUT] {source_label} (HopID: {source_id})")
            print(f"      Target: [LOUT] {target_label} (HopID: {target_id})")
            print(f"      Source Children: {source_children_str}")
            print(f"      Target Children: {target_children_str}")
            print(f"      Source Cost: Total={source_total_cost}, Self={source_self_cost}, Weight={source_weight}")
            print(f"      Target Cost: Total={target_total_cost}, Self={target_self_cost}, Weight={target_weight}")
            print(f"      Edge Info: ForwardCost={forward_cost}, ForwardWeight={forward_weight}, Forwarding={'Yes' if is_forwarding else 'No'}, CumulativeCost={cumulative_cost}")
            print(f"      Cost Analysis: Source Hop={u_cost:.2f} ({u_ratio:.2f}%), Target Hop={v_cost:.2f} ({v_ratio:.2f}%), Edge Network={edge_cost:.2f} ({edge_ratio:.2f}%)")
            print(f"      FType Info: Source InputFTypes={source_input_ftypes}, ReturnFType={source_return_ftype} -> Target InputFTypes={target_input_ftypes}, ReturnFType={target_return_ftype}")
            print(f"      Analysis: {'Data read from federated source -> Local processing' if i == 1 else 'FOUT to LOUT transition - potential data transfer point'}")
            print("      " + "-"*70)
        
        print("   " + "="*75)
        print(f"   Summary: Total {len(fout_to_lout_edges)} FOUT to LOUT transitions")
    
    # 6. Fed parents FOUT/LOUT analysis
    fed_parent_nodes = collect_fed_parents(G)
    if fed_parent_nodes:
        # Count FOUT/LOUT hops in Fed parents
        fed_parent_fout_hops = {}
        fed_parent_lout_hops = {}
        fed_parent_fout_cost = 0.0
        fed_parent_lout_cost = 0.0
        
        for node in fed_parent_nodes:
            if node == 'ROOT':
                continue
            kind = G.nodes[node].get('kind', '').upper()
            self_cost = G.nodes[node].get('self_cost', '0')
            try:
                cost = float(self_cost) if self_cost else 0.0
                if kind == 'FOUT':
                    fed_parent_fout_hops[node] = cost
                    fed_parent_fout_cost += cost
                elif kind == 'LOUT':
                    fed_parent_lout_hops[node] = cost
                    fed_parent_lout_cost += cost
            except (ValueError, TypeError):
                pass
        
        # Calculate ratios for Fed parents
        fed_parent_total_cost = fed_parent_fout_cost + fed_parent_lout_cost
        fed_parent_fout_cost_ratio = (fed_parent_fout_cost / fed_parent_total_cost * 100) if fed_parent_total_cost > 0 else 0.0
        fed_parent_lout_cost_ratio = (fed_parent_lout_cost / fed_parent_total_cost * 100) if fed_parent_total_cost > 0 else 0.0
        
        fed_parent_total_hops = len(fed_parent_fout_hops) + len(fed_parent_lout_hops)
        fed_parent_fout_count_ratio = (len(fed_parent_fout_hops) / fed_parent_total_hops * 100) if fed_parent_total_hops > 0 else 0.0
        fed_parent_lout_count_ratio = (len(fed_parent_lout_hops) / fed_parent_total_hops * 100) if fed_parent_total_hops > 0 else 0.0
        
        print(f"\n6. FED PARENTS FOUT/LOUT ANALYSIS")
        print(f"   Total Fed Parent Nodes: {len(fed_parent_nodes)}")
        print(f"   Fed Parent FOUT Hops: {len(fed_parent_fout_hops)} ({fed_parent_fout_count_ratio:.2f}% of Fed parents)")
        print(f"   Fed Parent LOUT Hops: {len(fed_parent_lout_hops)} ({fed_parent_lout_count_ratio:.2f}% of Fed parents)")
        print(f"   Fed Parent FOUT Cost: {fed_parent_fout_cost:.2f} ({fed_parent_fout_cost_ratio:.2f}% of Fed parent costs)")
        print(f"   Fed Parent LOUT Cost: {fed_parent_lout_cost:.2f} ({fed_parent_lout_cost_ratio:.2f}% of Fed parent costs)")
    
    print("\n" + "="*80)
    print("END OF COST ANALYSIS REPORT")
    print("="*80)
    
    # Restore stdout and get captured content
    sys.stdout = old_stdout
    report_content = captured_output.getvalue()
    
    # Print the report to console
    print(report_content)
    
    # Save report to text file
    report_file = os.path.join(output_dir, "cost_analysis_report.txt")
    report_file = get_unique_filename(report_file)
    with open(report_file, 'w', encoding='utf-8') as f:
        f.write(report_content)
    
    print(f"[INFO] Cost analysis report saved to: {report_file}")
    
    # Export to CSV
    export_cost_analysis_to_csv(G, fout_to_lout_edges, output_dir)


def get_unique_filename(base_filename: str) -> str:
    """Generate new filename by incrementing if existing file exists"""
    if not os.path.exists(base_filename):
        return base_filename
    
    name, ext = os.path.splitext(base_filename)
    counter = 1
    while True:
        new_filename = f"{name}_{counter}{ext}"
        if not os.path.exists(new_filename):
            return new_filename
        counter += 1


def format_number(num_str):
    """Format numbers as strings. Numbers with 3 or more digits are converted to mathematical exponential notation."""
    try:
        num = float(num_str)
        if num >= 1000 or num <= -1000:
            # Calculate exponent
            exponent = 0
            base = abs(num)
            while base >= 10:
                base /= 10
                exponent += 1
            
            sign = "-" if num < 0 else ""
            # Round to first decimal place
            base_rounded = round(base, 1)
            base_str = f"{sign}{base_rounded}"
            
            # Convert exponent to Unicode superscript
            superscript_map = {
                '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴',
                '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹',
                '+': '⁺', '-': '⁻'
            }
            
            exp_str = str(exponent)
            superscript_exp = ''.join(superscript_map[c] for c in exp_str)
            
            return f"{base_str}×10{superscript_exp}"
        else:
            # Round to first decimal place
            rounded_num = round(num, 1)
            # If integer after rounding, display as integer; otherwise display to first decimal place
            if rounded_num == int(rounded_num):
                return str(int(rounded_num))
            else:
                return str(rounded_num)
    except (ValueError, TypeError):
        return str(num_str)


def get_abbreviated_label(label):
    """
    Abbreviate labels using abbreviation dictionary.
    Example: "transferMatrixFromRemoteToLocal" -> "t2Loc"
    """
    if not label:
        return label
    
    # Split label words (by CamelCase, snake_case, spaces, etc.)
    # 1. CamelCase -> spaced
    spaced_label = re.sub(r'([a-z])([A-Z])', r'\1 \2', label)
    # 2. snake_case -> spaced
    spaced_label = spaced_label.replace('_', ' ')
    # 3. Split by spaces
    words = spaced_label.split()
    
    result = []
    for word in words:
        # Check operator abbreviation
        if (word.lower() == "op"):
            continue

        is_abbreviated = False
        for op, abbr in OPERATION_ABBR.items():
            if op.lower() == word.lower():
                result.append(abbr)
                is_abbreviated = True
                break
        # Check variable abbreviation
        if not is_abbreviated:
            for var, abbr in VARIABLE_ABBR.items():
                if var.lower() == word.lower():
                    result.append(abbr)
                    break

        if not is_abbreviated:
            result.append(word)                 
                
    # Connect words using separator character (·)
    abbreviated = '·'.join(result)
    abbreviated = truncate_label(abbreviated)

    return abbreviated


def truncate_label(label, max_length=8):
    """Limit label name to specified maximum length."""
    if not label or len(label) <= max_length:
        return label
    return label[:max_length-1]


def visualize_plan(filename: str, output_dir: str = "visualization_output", 
                node_cost_display: bool = True, edge_cost_display: bool = True, 
                cost_analysis: bool = True, percentage_mode: bool = False, 
                proportional_sizing: bool = False, debug: bool = False):
    if debug:
        print(f"[INFO] Visualizing file '{filename}'.")
        print(f"[INFO] Node cost display: {'Enabled' if node_cost_display else 'Disabled'}")
        print(f"[INFO] Edge cost display: {'Enabled' if edge_cost_display else 'Disabled'}")
    
    # Get input filename without extension for directory name
    input_basename = os.path.splitext(os.path.basename(filename))[0]
    # Create subdirectory based on input filename
    final_output_dir = os.path.join(output_dir, input_basename)
    os.makedirs(final_output_dir, exist_ok=True)
    
    if debug:
        print(f"[INFO] Output directory: {final_output_dir}")
    
    G, get_federated_type_info = build_dag_from_file(filename, debug=debug)
    if debug:
        print("Nodes:", G.nodes(data=True))
        print("Edges:", list(G.edges(data=True)))
    
    # Pre-calculate cost analysis for percentage mode and proportional sizing
    hop_costs, hop_ratios, total_compute_cost = analyze_hop_costs(G)
    edge_costs, edge_ratios, total_network_cost = analyze_edge_costs(G)

    if HAS_PYGRAPHVIZ:
        # Set larger node spacing (nodesep: horizontal spacing between nodes, ranksep: vertical spacing between levels)
        pos = graphviz_layout(G, prog='dot', args='-Grankdir=BT -Gnodesep=3 -Granksep=3')
    else:
        # For spring_layout, increase k value to ensure spacing between nodes
        pos = nx.spring_layout(G, seed=42, k=2.0)

    # Dynamically adjust overall graph size based on number of nodes
    node_count = len(G.nodes())
    fig_width = 15 + node_count / 8.0  # Increase width
    fig_height = 10 + node_count / 8.0  # Increase height
    plt.figure(figsize=(fig_width, fig_height), facecolor='white', dpi=300)
    ax = plt.gca()
    ax.set_facecolor('white')

    # Set node labels (format: id: hop name \n Total \n Self)
    labels = {}
    for n in G.nodes():
        # Basic information
        node_id = n
        label = G.nodes[n].get('label', n)
        total_cost = G.nodes[n].get('total', '')
        self_cost = G.nodes[n].get('self_cost', '')
        weight = G.nodes[n].get('weight', '')
        
        # Traverse child edges to calculate cumulative cost and forwarding cost totals
        child_cumulated_cost_sum = 0.0
        child_forward_cost_sum = 0.0
        
        if debug:
            print(f"\n[DEBUG] Calculating child costs for node {node_id}:")
        
        # 1. Find all edges coming into this node (child nodes)
        child_nodes = []
        for child, _, _ in G.in_edges(n, data=True):
            child_nodes.append(child)
        
        if debug:
            print(f"  Child nodes: {child_nodes}")
        
        # 2. Sum cumulative_cost and forward_cost for each child node
        for child_node in child_nodes:
            # Get edge data between current node and child node
            edge_data = G.get_edge_data(child_node, node_id)
            if edge_data:
                # Calculate cumulative cost
                if 'cumulative_cost' in edge_data and edge_data['cumulative_cost'] is not None:
                    try:
                        cumulative_cost = float(edge_data['cumulative_cost'])
                        if debug:
                            print(f"  Cumulative cost for child node {child_node}: {cumulative_cost}")
                        child_cumulated_cost_sum += cumulative_cost
                    except ValueError:
                        if debug:
                            print(f"  Failed to convert cumulative cost for child node {child_node}: {edge_data['cumulative_cost']}")
                
                # Calculate forwarding cost
                if 'forward_cost' in edge_data and edge_data['forward_cost'] is not None:
                    try:
                        if edge_data['forward_cost'] != '-1':  # Only for non-undiscovered edges
                            fwd_cost = float(edge_data['forward_cost'])
                            if debug:
                                print(f"  Forward_cost for child node {child_node}: {fwd_cost}")
                            child_forward_cost_sum += fwd_cost
                    except ValueError:
                        if debug:
                            print(f"  Failed to convert forward_cost for child node {child_node}: {edge_data['forward_cost']}")
        
        # First line of label: node ID, operation, total cost, weight
        first_line = f"{node_id}: {get_abbreviated_label(label)}"
        if node_cost_display:
            if percentage_mode:
                # Show percentages instead of absolute values (only total cost, no weight or parentheses)
                if total_cost:
                    try:
                        total_cost_val = float(total_cost)
                        total_cost_percentage = (total_cost_val / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
                        first_line += f"\nC: {total_cost_percentage:.1f}%"
                    except (ValueError, TypeError):
                        first_line += f"\nC: 0.0%"
                
                # No weight display in percentage mode
                # No second line with parentheses in percentage mode
                labels[n] = first_line
            else:
                # Show absolute values (original behavior)
                if total_cost:
                    formatted_total = format_number(total_cost)
                    first_line += f"\nC: {formatted_total}"
                if weight:
                    formatted_weight = format_number(weight)
                    first_line += f", W: {formatted_weight}"
                
                # Second line of label: Self Cost, child cumulative cost sum, child forwarding cost sum separated by slash (/)
                try:
                    self_cost_formatted = format_number(self_cost) if self_cost else "0"
                except (ValueError, TypeError):
                    self_cost_formatted = "0"
                
                child_cumulated_cost_formatted = format_number(child_cumulated_cost_sum)
                child_forward_cost_formatted = format_number(child_forward_cost_sum)
                
                if debug:
                    print(f"  Final cost summary: Self={self_cost_formatted}, Child Total={child_cumulated_cost_formatted}, Child Fwd={child_forward_cost_formatted}")
                second_line = f"({self_cost_formatted}/{child_cumulated_cost_formatted}/{child_forward_cost_formatted})"
                
                # Final label
                labels[n] = f"{first_line}\n{second_line}"
        else:
            # Display only node ID and label without cost information
            labels[n] = first_line

    # Collect all Fed parent nodes
    fed_parent_nodes = collect_fed_parents(G)

    # Collect nodes participating in LOUT<->FOUT transitions for coloring
    transition_fout_to_lout_nodes = set()
    transition_lout_to_fout_nodes = set()
    for u, v, d in G.edges(data=True):
        if v == 'ROOT' or u == 'ROOT':
            continue
        if 'is_discovered' in d and d['is_discovered']:
            u_kind = G.nodes[u].get('kind', '').upper()
            v_kind = G.nodes[v].get('kind', '').upper()
            if u_kind == 'FOUT' and v_kind == 'LOUT':
                transition_fout_to_lout_nodes.update([u, v])
            elif u_kind == 'LOUT' and v_kind == 'FOUT':
                transition_lout_to_fout_nodes.update([u, v])
    
    # Determine color for each node (based on kind and operation)
    def get_color(n):
        label = G.nodes[n].get('label', '').lower()
        k = G.nodes[n].get('kind', '').lower()

        # Highlight nodes involved in transitions first
        if n in transition_lout_to_fout_nodes:
            return 'red'
        if n in transition_fout_to_lout_nodes:
            return 'blue'
        
        # Check if operation starts with "fed" (e.g., "Fed X")
        if label.startswith('fed '):
            return 'red'
        # Check if this node is in the Fed parent nodes set
        elif n in fed_parent_nodes:
            # Apply original color rules
            if k == 'fout':
                return 'tomato'
            elif k == 'lout':
                return 'dodgerblue'
            elif k == 'nref':
                return 'mediumpurple'
            elif k == 'nref(top)':
                return 'darkviolet'
            else:
                return 'mediumseagreen'
        else:
            # Not a Fed node and not a parent of Fed node -> gray
            return 'gray'

    # Determine node shape by execution type: FED exec -> square, local/others -> circle
    square_nodes = []
    circle_nodes = []
    for n in G.nodes():
        exec_type = str(G.nodes[n].get('exec_type', '')).lower()
        if exec_type == 'fed':
            square_nodes.append(n)
        else:
            circle_nodes.append(n)

    square_colors = [get_color(n) for n in square_nodes]
    circle_colors = [get_color(n) for n in circle_nodes]

    # Calculate node sizes based on proportional_sizing option (using self cost)
    if proportional_sizing:
        # Base size range: 800 to 4000 for better visibility 
        min_size = 800
        max_size = 4000
        
        # Calculate self cost percentages for all nodes
        self_cost_percentages = {}
        for node in G.nodes():
            if node == 'ROOT':
                self_cost_percentages[node] = 0.0
                continue
            self_cost = G.nodes[node].get('self_cost', '0')
            try:
                cost = float(self_cost) if self_cost else 0.0
                percentage = (cost / total_compute_cost * 100) if total_compute_cost > 0 else 0.0
                self_cost_percentages[node] = percentage
            except (ValueError, TypeError):
                self_cost_percentages[node] = 0.0
        
        # Find max percentage for scaling
        max_percentage = max(self_cost_percentages.values()) if self_cost_percentages else 1.0
        if max_percentage == 0:
            max_percentage = 1.0
        
        # Calculate sizes for each node group based on self cost
        square_sizes = []
        for node in square_nodes:
            percentage = self_cost_percentages.get(node, 0.0)
            if percentage > 0:
                normalized_percentage = percentage / max_percentage
                size = min_size + normalized_percentage * (max_size - min_size)
            else:
                size = min_size
            square_sizes.append(size)
        
        circle_sizes = []
        for node in circle_nodes:
            percentage = self_cost_percentages.get(node, 0.0)
            if percentage > 0:
                normalized_percentage = percentage / max_percentage
                size = min_size + normalized_percentage * (max_size - min_size)
            else:
                size = min_size
            circle_sizes.append(size)
    else:
        # Fixed node size (original behavior)
        node_size = 1200
        square_sizes = [node_size] * len(square_nodes)
        circle_sizes = [node_size] * len(circle_nodes)

    # Draw each node group separately
    node_collection_square = nx.draw_networkx_nodes(G, pos, nodelist=square_nodes, node_size=square_sizes, 
                                                    node_color=square_colors, node_shape='s', ax=ax)
    node_collection_circle = nx.draw_networkx_nodes(G, pos, nodelist=circle_nodes, node_size=circle_sizes, 
                                                    node_color=circle_colors, node_shape='o', ax=ax)

    # Adjust zorder (nodes:1, edges:2, labels:3)
    node_collection_square.set_zorder(1)
    node_collection_circle.set_zorder(1)

    # Draw edges with different colors based on FOUT to LOUT transition and ROOT node connection
    
    
    # 1. Check for FOUT to LOUT transitions (all transitions)
    fout_to_lout_edges = []
    normal_edges = []
    
    for u, v, d in G.edges(data=True):
        if v == 'ROOT' or u == 'ROOT':
            continue
        if 'is_discovered' in d and d['is_discovered']:
            # Check if this edge represents a FOUT to LOUT transition
            u_kind = G.nodes[u].get('kind', '').upper()
            v_kind = G.nodes[v].get('kind', '').upper()
            
            if u_kind == 'FOUT' and v_kind == 'LOUT':
                fout_to_lout_edges.append((u, v))
            else:
                normal_edges.append((u, v))
    
    # 2. All edges connected to ROOT node (both discovered/undiscovered shown in black)
    root_edges = [(u, v) for u, v, d in G.edges(data=True) 
                 if v == 'ROOT' or u == 'ROOT']
    
    # 3. Undiscovered edges (excluding those connected to ROOT node)
    undiscovered_edges = [(u, v) for u, v, d in G.edges(data=True) 
                         if ('is_discovered' not in d or not d['is_discovered'])
                         and v != 'ROOT' and u != 'ROOT']
    
    if debug:
        print(f"\n[DEBUG] FOUT to LOUT edges: {fout_to_lout_edges}")
        print(f"[DEBUG] Normal edges: {normal_edges}")
        print(f"[DEBUG] ROOT connected edges: {root_edges}")
        print(f"[DEBUG] Undiscovered edges: {undiscovered_edges}")
    
    # Generate comprehensive cost analysis report
    if cost_analysis:
        generate_cost_analysis_report(G, fout_to_lout_edges, get_federated_type_info, filename, final_output_dir)
    
    # Initialize collections as None
    fout_to_lout_collection = None
    normal_edges_collection = None
    root_edges_collection = None
    undiscovered_collection = None
    network_cost_edges_collection = None
    no_network_cost_edges_collection = None
    
    if percentage_mode:
        # In percentage mode, classify edges based on network cost
        network_cost_edges = []
        no_network_cost_edges = []
        
        # Classify all edges based on network cost
        all_edges = fout_to_lout_edges + normal_edges + root_edges + undiscovered_edges
        
        for u, v in all_edges:
            if u == 'ROOT' or v == 'ROOT':
                no_network_cost_edges.append((u, v))
                continue
                
            edge_data = G.get_edge_data(u, v)
            has_network_cost = False
            
            if edge_data and 'forward_cost' in edge_data:
                try:
                    forward_cost = float(edge_data['forward_cost'])
                    if forward_cost > 0:
                        has_network_cost = True
                except (ValueError, TypeError):
                    pass
            
            if has_network_cost:
                network_cost_edges.append((u, v))
            else:
                no_network_cost_edges.append((u, v))
        
        # Draw edges with network cost: red thick lines
        if network_cost_edges:
            network_cost_edges_collection = nx.draw_networkx_edges(G, pos, edgelist=network_cost_edges, 
                                      arrows=True, arrowstyle='->', 
                                      edge_color='red', width=3.0, ax=ax)
        
        # Draw edges without network cost: black thin lines
        if no_network_cost_edges:
            no_network_cost_edges_collection = nx.draw_networkx_edges(G, pos, edgelist=no_network_cost_edges, 
                                      arrows=True, arrowstyle='->', 
                                      edge_color='black', width=1.0, ax=ax)
    else:
        # Original behavior for non-percentage mode
        if proportional_sizing:
            # Base width range: 0.5 to 5.0
            min_width = 0.5
            max_width = 5.0
            
            # Calculate widths for FOUT to LOUT transition edges
            fout_to_lout_widths = []
            for u, v in fout_to_lout_edges:
                percentage = edge_ratios.get((u, v), 0.0)
                if percentage > 0:
                    width = min_width + (percentage / 100.0) * (max_width - min_width)
                else:
                    width = min_width
                fout_to_lout_widths.append(width)
            
            # Calculate widths for normal edges
            normal_widths = []
            for u, v in normal_edges:
                percentage = edge_ratios.get((u, v), 0.0)
                if percentage > 0:
                    width = min_width + (percentage / 100.0) * (max_width - min_width)
                else:
                    width = min_width
                normal_widths.append(width)
            
            # Root edges and undiscovered edges get fixed widths
            root_width = 1.0
            undiscovered_width = 2.5
        else:
            # Fixed edge widths (original behavior)
            fout_to_lout_widths = [2.0] * len(fout_to_lout_edges)
            normal_widths = [1.0] * len(normal_edges)
            root_width = 1.0
            undiscovered_width = 2.5
        
        # FOUT to LOUT transition edges: red
        if fout_to_lout_edges:
            fout_to_lout_collection = nx.draw_networkx_edges(G, pos, edgelist=fout_to_lout_edges, 
                                  arrows=True, arrowstyle='->', 
                                  edge_color='red', width=fout_to_lout_widths, ax=ax)
        
        # Normal edges: black
        if normal_edges:
            normal_edges_collection = nx.draw_networkx_edges(G, pos, edgelist=normal_edges, 
                                  arrows=True, arrowstyle='->', 
                                  edge_color='black', width=normal_widths, ax=ax)
        
        # All ROOT node connected edges: black
        if root_edges:
            root_edges_collection = nx.draw_networkx_edges(G, pos, edgelist=root_edges, 
                                  arrows=True, arrowstyle='->', 
                                  edge_color='black', width=root_width, ax=ax)
        
        # Undiscovered edges: purple thick line
        if undiscovered_edges:
            undiscovered_collection = nx.draw_networkx_edges(G, pos, edgelist=undiscovered_edges, 
                                                               arrows=True, arrowstyle='->', 
                                                               edge_color='purple', width=undiscovered_width, alpha=0.7, ax=ax)
    
    # Helper function for setting z-order
    def set_zorder_for_collection(collection, z=2):
        if isinstance(collection, list):
            for ec in collection:
                ec.set_zorder(z)
        elif collection is not None:
            collection.set_zorder(z)
    
    # Set z-order for all edge collections
    set_zorder_for_collection(fout_to_lout_collection)
    set_zorder_for_collection(normal_edges_collection)
    set_zorder_for_collection(root_edges_collection)
    set_zorder_for_collection(undiscovered_collection)
    set_zorder_for_collection(network_cost_edges_collection)
    set_zorder_for_collection(no_network_cost_edges_collection)

    # Add edge labels (forwarding cost and weight info) - set background completely transparent
    edge_labels = {}
    
    # Add edge labels only when edge_cost_display is True
    if edge_cost_display:
        # Display discovered edges in C/W/CC format (excluding ROOT node connections)
        for u, v, d in G.edges(data=True):
            # Don't display labels for edges connected to ROOT node
            if v == 'R' or u == 'R':
                continue
                
            # Display information for discovered edges
            if 'is_discovered' in d and d['is_discovered'] and 'forward_cost' in d and 'forward_weight' in d:
                label_parts = []

                if percentage_mode:
                    # Only show label if there is network cost
                    has_network_cost = False
                    try:
                        forward_cost = float(d.get('forward_cost', '0'))
                        if forward_cost > 0:
                            has_network_cost = True
                    except (ValueError, TypeError):
                        pass
                    
                    if has_network_cost:
                        edge_percentage = edge_ratios.get((u, v), 0.0)
                        label_parts.append(f"FC:{edge_percentage:.1f}%")
                    # If no network cost, don't add any labels (empty label_parts)
                else:
                    # Show absolute values (original behavior)
                    # Add cumulative cost if available
                    if 'cumulative_cost' in d and d['cumulative_cost'] is not None:
                        cumulative_cost_formatted = format_number(d['cumulative_cost'])
                        label_parts.append(f"C:{cumulative_cost_formatted}")

                    # Forwarding cost 
                    forward_cost_formatted = format_number(d['forward_cost'])
                    label_parts.append(f"FC:{forward_cost_formatted}")
                    
                    # Weight
                    forward_weight_formatted = format_number(d['forward_weight'])
                    label_parts.append(f"FW:{forward_weight_formatted}")
                
                if label_parts:  # Only add label if there are parts to show
                    edge_labels[(u, v)] = "\n".join(label_parts)
            # Display undiscovered edges as "Undiscovered" (but not in percentage mode)
            elif ('is_discovered' not in d or not d['is_discovered']) and 'forward_cost' in d and 'forward_weight' in d:
                if not percentage_mode:  # Don't show "Undiscovered" in percentage mode
                    edge_labels[(u, v)] = "Undiscovered"

    # Add edge labels - set background completely transparent
    if edge_labels:
        edge_label_dict = nx.draw_networkx_edge_labels(G, pos, edge_labels=edge_labels, 
                                                     font_size=7, font_color='darkblue',
                                                     bbox=dict(boxstyle="round", fc="w", ec="none", alpha=0),
                                                     ax=ax)
        
        # Set label background directly transparent
        for key, text in edge_label_dict.items():
            text.set_bbox(dict(boxstyle="round", fc="none", ec="none", alpha=0))

    # Node labels - set background completely transparent
    label_dict = nx.draw_networkx_labels(G, pos, labels=labels, font_size=8, 
                                       bbox=dict(boxstyle="round", fc="w", ec="none", alpha=0),
                                       ax=ax)
    
    # Set node label background directly transparent
    for text in label_dict.values():
        text.set_zorder(3)
        text.set_bbox(dict(boxstyle="round", fc="none", ec="none", alpha=0))

    # Set desired title
    plt.title("Program Level Federated Plan", fontsize=16, fontweight="bold")

    # Node type legend (top left)
    plt.scatter(0.05, 0.95, color='dodgerblue', s=150, transform=ax.transAxes)
    plt.scatter(0.18, 0.95, color='tomato', s=150, transform=ax.transAxes)
    plt.scatter(0.31, 0.95, color='mediumpurple', s=150, transform=ax.transAxes)

    plt.text(0.08, 0.95, "LOUT", fontsize=10, va='center', transform=ax.transAxes)
    plt.text(0.21, 0.95, "FOUT", fontsize=10, va='center', transform=ax.transAxes)
    plt.text(0.34, 0.95, "NREF", fontsize=10, va='center', transform=ax.transAxes)
    
    # Edge related legend (top right)
    legend_x = 0.98  # Top right x coordinate
    legend_y = 0.98  # Top right y coordinate
    legend_spacing = 0.05  # Spacing between items
    
    # Label legend (text only)
    if node_cost_display:
        plt.text(legend_x, legend_y, "[Node LABEL]\nhopID: hopNam\nC: Total Cost, W: Weight\n(Self / Child Cum. Cost / Child Fwd. Cost)", 
                fontsize=12, ha='right', va='top', transform=ax.transAxes)
    else:
        plt.text(legend_x, legend_y, "[Node LABEL]\nhopID: hopNam", 
                fontsize=12, ha='right', va='top', transform=ax.transAxes)

    plt.axis("off")

    # Generate output filename based on input filename
    input_filename = os.path.basename(filename)
    base_output_filename = os.path.splitext(input_filename)[0]
    
    # Set filename suffix based on cost display options
    suffix = ""
    if not node_cost_display:
        suffix += "_no_node_cost"
    if not edge_cost_display:
        suffix += "_no_edge_cost"
    if percentage_mode:
        suffix += "_percentage"
    if proportional_sizing:
        suffix += "_proportional"
    
    base_output_filename += suffix + ".png"
    output_filename = os.path.join(final_output_dir, base_output_filename)
    
    # Handle duplicate filenames
    output_filename = get_unique_filename(output_filename)
    
    plt.savefig(output_filename, bbox_inches='tight', dpi=300)
    print(f"\n[INFO] Visualization result saved to '{output_filename}'.")
    plt.close()


def main():
    
    # Set up argument parser
    parser = argparse.ArgumentParser(description='Tool for visualizing federated plans')
    parser.add_argument('trace_file', help='Path to the trace file to visualize')
    parser.add_argument('--no-node-cost', action='store_true', help='Do not display node cost information')
    parser.add_argument('--no-edge-cost', action='store_true', help='Do not display edge cost information')
    parser.add_argument('--no-cost', action='store_true', help='Do not display any cost information (applies both --no-node-cost and --no-edge-cost)')
    parser.add_argument('--no-cost-analysis', action='store_true', help='Do not generate cost analysis report')
    parser.add_argument('--absolute', action='store_true', help='Display costs as absolute values instead of percentages (default: percentage mode)')
    parser.add_argument('--fixed-size', action='store_true', help='Use fixed node and edge sizes instead of proportional sizing (default: proportional mode)')
    parser.add_argument('--output-dir', default='visualization_output', help='Output directory path (default: visualization_output)')
    parser.add_argument('--debug', action='store_true', help='Enable debug output')
    
    # Parse arguments
    args = parser.parse_args()
    
    # Check file existence
    if not os.path.exists(args.trace_file):
        print(f"[ERROR] File '{args.trace_file}' not found.")
        sys.exit(1)
    
    # Set cost display options (default: percentage and proportional modes)
    node_cost_display = not (args.no_node_cost or args.no_cost)
    edge_cost_display = not (args.no_edge_cost or args.no_cost)
    cost_analysis = not args.no_cost_analysis
    percentage_mode = not args.absolute  # Default: percentage mode (True), unless --absolute is specified
    proportional_sizing = not args.fixed_size  # Default: proportional sizing (True), unless --fixed-size is specified
    
    # Execute visualization
    visualize_plan(args.trace_file, args.output_dir, node_cost_display, edge_cost_display, 
                   cost_analysis, percentage_mode, proportional_sizing, args.debug)


if __name__ == '__main__':
    main()
