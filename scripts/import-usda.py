"""从官方固定版本ZIP生成最小可追溯摘录，不修改应用运行时目录。"""
from pathlib import Path
from zipfile import ZipFile
from decimal import Decimal
import csv
import io
import json
import hashlib
import argparse

ROOT = Path(__file__).resolve().parent.parent
FIELDS = {'1008': ('energyKcal', 'KCAL'), '1003': ('proteinGrams', 'G'),
          '1004': ('fatGrams', 'G'), '1005': ('carbohydrateGrams', 'G')}
SELECTED = {
    '169756': ('长粒白米（生，未强化）', 'RAW', ['生大米', '大米', '米饭'],
        'Rice, white, long-grain, regular, raw, unenriched'),
    '168935': ('长粒白米饭（熟，未强化，加盐）', 'COOKED', ['熟米饭', '米饭'],
        'Rice, white, long-grain, regular, cooked, unenriched, with salt'),
    '171287': ('全鸡蛋（生，鲜）', 'RAW', ['生鸡蛋', '鸡蛋'], 'Egg, whole, raw, fresh')}
URL = 'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip'

def build(archive):
    with ZipFile(archive) as z:
        def rows(name):
            path = next(n for n in z.namelist() if n.endswith('/' + name))
            with z.open(path) as file:
                yield from csv.DictReader(io.TextIOWrapper(file, encoding='utf-8-sig'))
        foods = {r['fdc_id']: r for r in rows('food.csv') if r['fdc_id'] in SELECTED}
        nutrients = {r['id']: r for r in rows('nutrient.csv') if r['id'] in FIELDS}
        facts = {}
        for r in rows('food_nutrient.csv'):
            if r['fdc_id'] in SELECTED and r['nutrient_id'] in FIELDS:
                key = (r['fdc_id'], r['nutrient_id'])
                if key in facts:
                    raise ValueError('重复营养项目，需人工核查')
                facts[key] = r
        output = []
        for fdc_id, (name, state, aliases, expected) in SELECTED.items():
            food = foods[fdc_id]
            if food['description'] != expected:
                raise ValueError('食品原始描述变化，拒绝静默导入')
            values = {}
            raw_facts = []
            for nutrient_id, (field, unit) in FIELDS.items():
                if nutrients[nutrient_id]['unit_name'].upper() != unit:
                    raise ValueError('单位不符，拒绝导入')
                fact = facts.get((fdc_id, nutrient_id))
                value = None if fact is None or fact['amount'] == '' else fact['amount']
                if value is not None and (not Decimal(value).is_finite() or Decimal(value) < 0):
                    raise ValueError('非法营养数值')
                values[field] = value  # 十进制字符串保留原始精度，缺失为null。
                if fact is not None:
                    raw_facts.append(fact)
            output.append({'fdcId': int(fdc_id), 'name': name, 'state': state,
                'aliases': aliases, 'per100g': values, 'originalFood': food,
                'originalNutrients': raw_facts,
                'sourceUrl': f'https://fdc.nal.usda.gov/food-details/{fdc_id}/nutrients'})
    return {'source': 'USDA FoodData Central', 'dataset': 'SR Legacy 2018-04',
            'archiveUrl': URL, 'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
            'license': 'CC0-1.0', 'referenceGrams': 100,
            'nutrientDefinitions': nutrients, 'foods': output}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, default=ROOT / '.tools/usda-sr2018.zip')
    args = parser.parse_args()
    result = build(args.archive)
    target = ROOT / 'data/usda/selected-foods.json'
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for food in result['foods']:
        print(food['fdcId'], food['name'], food['per100g'])
    print('已生成', target.name, '原始归档SHA256:', result['archiveSha256'])
