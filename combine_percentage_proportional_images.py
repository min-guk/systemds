#!/usr/bin/env python3
import os
import glob
from PIL import Image, ImageDraw, ImageFont
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
import numpy as np
from datetime import datetime
import argparse

def find_most_recent_percentage_proportional(directory):
    """각 하위 디렉토리에서 가장 최근의 percentage_proportional.png 파일을 찾습니다."""
    pattern = os.path.join(directory, "*percentage_proportional*.png")
    files = glob.glob(pattern)
    
    if not files:
        return None
    
    # 파일을 수정 시간 기준으로 정렬하여 가장 최근 파일 반환
    files_with_time = [(f, os.path.getmtime(f)) for f in files]
    files_with_time.sort(key=lambda x: x[1], reverse=True)
    
    return files_with_time[0][0]

def combine_images(single_row=False, exclude_folders=None):
    """visualization_output의 하위 디렉토리에서 가장 최근 percentage_proportional.png 파일들을 결합합니다."""
    base_dir = "visualization_output"
    
    if exclude_folders is None:
        exclude_folders = []
    
    # 하위 디렉토리 목록 가져오기 (제외할 폴더 필터링)
    subdirs = [d for d in os.listdir(base_dir) 
               if os.path.isdir(os.path.join(base_dir, d)) and d not in exclude_folders]
    
    # 각 하위 디렉토리에서 가장 최근 percentage_proportional.png 파일 찾기
    images_data = []
    for subdir in sorted(subdirs):
        subdir_path = os.path.join(base_dir, subdir)
        recent_file = find_most_recent_percentage_proportional(subdir_path)
        
        if recent_file:
            images_data.append({
                'path': recent_file,
                'folder_name': subdir,
                'mtime': os.path.getmtime(recent_file)
            })
            print(f"Found: {recent_file} (Modified: {datetime.fromtimestamp(os.path.getmtime(recent_file))})")
    
    if not images_data:
        print("No percentage_proportional.png files found!")
        return
    
    # 이미지 개수에 따른 그리드 설정
    n_images = len(images_data)
    
    if single_row:
        n_rows = 1
        n_cols = n_images
    else:
        n_rows = 2
        n_cols = (n_images + 1) // 2  # 2행으로 나누기
    
    # 각 서브플롯의 크기 설정
    fig_width = n_cols * 6
    fig_height = n_rows * 5
    
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(fig_width, fig_height))
    
    # axes를 1차원 배열로 변환
    if n_images == 1:
        axes = [axes]
    elif n_rows == 1 or n_cols == 1:
        axes = axes.flatten() if hasattr(axes, 'flatten') else [axes]
    else:
        axes = axes.flatten()
    
    # 각 이미지를 서브플롯에 배치
    for idx, img_data in enumerate(images_data):
        if idx >= len(axes):
            break
            
        ax = axes[idx]
        
        try:
            # 이미지 로드
            img = Image.open(img_data['path'])
            
            # 이미지 표시
            ax.imshow(img)
            ax.axis('off')
            
            # 폴더명을 상단 가운데에 표시
            ax.set_title(img_data['folder_name'], fontsize=14, fontweight='bold', pad=10)
            
        except Exception as e:
            print(f"Error loading {img_data['path']}: {e}")
            ax.text(0.5, 0.5, f"Error loading\n{img_data['folder_name']}", 
                   ha='center', va='center', transform=ax.transAxes)
            ax.axis('off')
    
    # 남은 빈 서브플롯 숨기기
    for idx in range(len(images_data), len(axes)):
        axes[idx].axis('off')
    
    # 레이아웃 조정
    plt.tight_layout()
    
    # 결과 저장
    output_filename = f"combined_percentage_proportional_{datetime.now().strftime('%Y%m%d_%H%M%S')}.png"
    plt.savefig(output_filename, dpi=150, bbox_inches='tight')
    print(f"\nCombined image saved as: {output_filename}")
    
    # 화면에 표시
    plt.show()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Combine percentage_proportional.png images from subdirectories')
    parser.add_argument('--single-row', action='store_true', 
                        help='Arrange images in a single row instead of 2 rows')
    parser.add_argument('--exclude', nargs='+', default=[], 
                        help='Folder names to exclude from combining')
    
    args = parser.parse_args()
    
    if args.exclude:
        print(f"Excluding folders: {args.exclude}")
    
    combine_images(single_row=args.single_row, exclude_folders=args.exclude)