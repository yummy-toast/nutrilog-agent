import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile
import csv
import io
import unittest

spec = importlib.util.spec_from_file_location('importer', Path(__file__).with_name('import-usda.py'))
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)

class ImportTest(unittest.TestCase):
    def run_fixture(self, wrong_unit=False, duplicate=False, wrong_description=False):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.zip'
            tables = {
                'food.csv': [{'fdc_id': key, 'description': 'wrong' if wrong_description else value[3]}
                             for key, value in importer.SELECTED.items()],
                'nutrient.csv': [{'id': key, 'unit_name': 'KJ' if wrong_unit else unit}
                                 for key, (_, unit) in importer.FIELDS.items()],
                'food_nutrient.csv': [{'fdc_id': '169756', 'nutrient_id': '1008', 'amount': '0'}]}
            if duplicate:
                tables['food_nutrient.csv'] *= 2
            with ZipFile(path, 'w') as archive:
                for name, rows in tables.items():
                    buffer = io.StringIO()
                    writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
                    writer.writeheader()
                    writer.writerows(rows)
                    archive.writestr('fixture/' + name, buffer.getvalue())
            return importer.build(path)
    def test_missing_is_not_zero(self):
        food = self.run_fixture()['foods'][0]
        self.assertEqual(food['per100g']['energyKcal'], '0')
        self.assertIsNone(food['per100g']['proteinGrams'])
    def test_rejects_wrong_units(self):
        with self.assertRaisesRegex(ValueError, '单位'):
            self.run_fixture(wrong_unit=True)
    def test_rejects_duplicate_nutrients(self):
        with self.assertRaisesRegex(ValueError, '重复'):
            self.run_fixture(duplicate=True)
    def test_rejects_changed_description(self):
        with self.assertRaisesRegex(ValueError, '描述'):
            self.run_fixture(wrong_description=True)

if __name__ == '__main__':
    unittest.main()
