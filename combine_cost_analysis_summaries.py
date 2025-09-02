#!/usr/bin/env python3
"""
Script to extract the highest numbered cost_analysis_summary_X.csv files 
from each folder under visualization_output and combine them into a single CSV file.
"""

import os
import re
import pandas as pd
from pathlib import Path

def find_highest_numbered_summary(folder_path):
    """Find the cost_analysis_summary file with the highest number in a folder."""
    summary_files = []
    
    # Look for cost_analysis_summary.csv (base file, treat as number 0)
    base_file = folder_path / "cost_analysis_summary.csv"
    if base_file.exists():
        summary_files.append((base_file, 0))
    
    # Look for cost_analysis_summary_X.csv files
    for file in folder_path.glob("cost_analysis_summary_*.csv"):
        match = re.search(r'cost_analysis_summary_(\d+)\.csv$', file.name)
        if match:
            number = int(match.group(1))
            summary_files.append((file, number))
    
    if not summary_files:
        return None
    
    # Return the file with the highest number
    return max(summary_files, key=lambda x: x[1])[0]

def main():
    visualization_output_path = Path("visualization_output")
    
    if not visualization_output_path.exists():
        print("Error: visualization_output directory not found")
        return
    
    combined_dataframes = []
    processed_folders = []
    
    # Process each subfolder
    for folder in visualization_output_path.iterdir():
        if folder.is_dir():
            print(f"Processing folder: {folder.name}")
            
            # Find the highest numbered cost_analysis_summary_X.csv
            summary_file = find_highest_numbered_summary(folder)
            
            if summary_file:
                print(f"  Found summary file: {summary_file.name}")
                
                try:
                    # Read the CSV file
                    df = pd.read_csv(summary_file)
                    
                    # Add folder name as a new column
                    df['Folder_Name'] = folder.name
                    
                    combined_dataframes.append(df)
                    processed_folders.append(folder.name)
                    print(f"  ✓ Read {len(df)} rows from {summary_file.name}")
                    
                except Exception as e:
                    print(f"  ✗ Error reading {summary_file.name}: {e}")
            else:
                print(f"  ✗ No cost_analysis_summary_X.csv found in {folder.name}")
    
    # Combine all dataframes
    if combined_dataframes:
        combined_df = pd.concat(combined_dataframes, ignore_index=True)
        
        # Reorder columns to have Folder_Name first
        columns = ['Folder_Name'] + [col for col in combined_df.columns if col != 'Folder_Name']
        combined_df = combined_df[columns]
        
        # Save to output file
        output_file = "combined_cost_analysis_summary.csv"
        combined_df.to_csv(output_file, index=False)
        
        print(f"\n✓ Successfully created {output_file}")
        print(f"  Combined data from {len(processed_folders)} folders: {', '.join(processed_folders)}")
        print(f"  Total rows: {len(combined_df)}")
    else:
        print("\n✗ No cost analysis summary files found in any folder")

if __name__ == "__main__":
    main()