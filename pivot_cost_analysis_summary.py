#!/usr/bin/env python3
"""
Script to convert combined_cost_analysis_summary.csv to a pivot table format
where rows are folder names, columns are metrics with percentages, and values are percentage values.
"""

import pandas as pd
from pathlib import Path

def main():
    input_file = "combined_cost_analysis_summary.csv"
    output_file = "cost_analysis_summary_pivot.csv"
    
    # Check if input file exists
    if not Path(input_file).exists():
        print(f"Error: {input_file} not found")
        return
    
    # Read the CSV file
    df = pd.read_csv(input_file)
    print(f"Loaded {len(df)} rows from {input_file}")
    
    # Filter rows that have percentage values (non-null and not empty string)
    df_with_percentage = df[df['Percentage'].notna() & (df['Percentage'] != '')]
    print(f"Found {len(df_with_percentage)} rows with percentage values")
    
    # Convert percentage strings to float values (remove % sign)
    df_with_percentage['Percentage_Value'] = df_with_percentage['Percentage'].str.rstrip('%').astype(float)
    
    # Create pivot table
    pivot_df = df_with_percentage.pivot_table(
        index='Folder_Name',
        columns='Metric',
        values='Percentage_Value',
        aggfunc='first'  # Use first value if there are duplicates
    )
    
    # Sort columns alphabetically for better readability
    pivot_df = pivot_df.reindex(sorted(pivot_df.columns), axis=1)
    
    # Save to CSV
    pivot_df.to_csv(output_file)
    
    print(f"\n✓ Successfully created {output_file}")
    print(f"  Dimensions: {pivot_df.shape[0]} folders × {pivot_df.shape[1]} metrics")
    print(f"\nFolders included:")
    for folder in pivot_df.index:
        print(f"  - {folder}")
    print(f"\nMetrics included:")
    for metric in pivot_df.columns[:5]:  # Show first 5 metrics
        print(f"  - {metric}")
    if len(pivot_df.columns) > 5:
        print(f"  ... and {len(pivot_df.columns) - 5} more metrics")

if __name__ == "__main__":
    main()