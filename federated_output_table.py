import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
import matplotlib.patches as patches

# CSV 파일 읽기
df = pd.read_csv('/home/mingu/systemds/getFederatedOut.csv')

# 고유한 Operation Type 리스트
operation_types = df['Operation Type'].unique()

# 각 Operation Type에 대해 색상 설정 (번갈아가며 흰색과 회색)
colors = {}
for i, op_type in enumerate(operation_types):
    colors[op_type] = 'white' if i % 2 == 0 else 'lightgray'

# 각 행에 대해 배경색 설정
row_colors = []
for _, row in df.iterrows():
    base_color = colors[row['Operation Type']]
    if row['isSupported'] == 'X':
        # X이면 더 어두운 회색으로
        if base_color == 'white':
            row_colors.append('darkgray')
        else:
            row_colors.append('dimgray')
    else:
        row_colors.append(base_color)

# 그림 생성
fig, ax = plt.subplots(figsize=(20, 30))

# 테이블 생성
table = ax.table(cellText=df.values,
                colLabels=df.columns,
                cellLoc='left',
                loc='center',
                colWidths=[0.15, 0.15, 0.08, 0.45, 0.17])

# 테이블 스타일 설정
table.auto_set_font_size(False)
table.set_fontsize(8)
table.scale(1, 2)

# 헤더 스타일 설정
for i in range(len(df.columns)):
    table[(0, i)].set_facecolor('#4CAF50')
    table[(0, i)].set_text_props(weight='bold', color='white')

# 각 행에 색상 적용
for i in range(len(df)):
    for j in range(len(df.columns)):
        table[(i+1, j)].set_facecolor(row_colors[i])
        
        # isSupported 컬럼에 특별한 스타일 적용
        if j == 2:  # isSupported 컬럼 인덱스
            if df.iloc[i, j] == 'X':
                table[(i+1, j)].set_text_props(weight='bold', color='white')
            else:
                table[(i+1, j)].set_text_props(weight='bold', color='green')

# 축 제거
ax.axis('off')

# 제목 설정
plt.title('Federated Operations Support Table', fontsize=16, fontweight='bold', pad=20)

# 범례 추가
legend_elements = [
    patches.Patch(color='white', label='Operation Type (Even)'),
    patches.Patch(color='lightgray', label='Operation Type (Odd)'),
    patches.Patch(color='darkgray', label='Not Supported (X) - Even'),
    patches.Patch(color='dimgray', label='Not Supported (X) - Odd')
]
ax.legend(handles=legend_elements, loc='upper right', bbox_to_anchor=(1, 1))

# 그래프 저장
plt.tight_layout()
plt.savefig('/home/mingu/systemds/federated_output_table.png', dpi=300, bbox_inches='tight')
print("테이블이 '/home/mingu/systemds/federated_output_table.png'에 저장되었습니다.")

# 그래프 출력
plt.show()