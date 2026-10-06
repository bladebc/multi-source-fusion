"""Use system CJK fonts when Matplotlib's cache does not include them."""
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib import font_manager


def configure_fonts():
    preferred = ['Noto Sans CJK SC', 'WenQuanYi Micro Hei', 'SimHei']
    # macOS fonts can be absent from Matplotlib's default font discovery.
    for path in (Path('/System/Library/Fonts/STHeiti Medium.ttc'),
                 Path('/System/Library/Fonts/STHeiti Light.ttc')):
        if path.is_file():
            font_manager.fontManager.addfont(str(path))
            preferred.append(font_manager.FontProperties(fname=str(path)).get_name())
    plt.rcParams['font.sans-serif'] = preferred + ['DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
