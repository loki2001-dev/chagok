import tempfile
import unittest
from pathlib import Path
from build_place_catalogue import convert

class CatalogueTest(unittest.TestCase):
    def test_quoted_bom_active_rows_and_stable_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'repair.csv'
            path.write_text('\ufeff자동차정비업체명,소재지도로명주소,위도,경도,영업상태\n"정비,센터",서울,37.5,127,1\n폐업,서울,37,127,2\n',encoding='utf-8')
            rows, skipped=convert(path,'REPAIR')
            self.assertEqual(skipped,1)
            self.assertEqual(rows[0]['name'],'정비,센터')
            old=rows[0]['id']
            path.write_text(path.read_text().replace('37.5','37.6'))
            self.assertEqual(convert(path,'REPAIR')[0][0]['id'],old)
    def test_zero_padded_operating_status_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'repair.csv'
            path.write_text('자동차정비업체명,소재지도로명주소,위도,경도,영업상태\n정비소,서울,37.5,127,01\n폐업,서울,37,127,03\n')
            rows, skipped=convert(path,'REPAIR')
            self.assertEqual(len(rows),1)
            self.assertEqual(skipped,1)
    def test_invalid_coordinates_fail_before_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fuel.csv'
            path.write_text('주유소명,소재지도로명주소,위도,경도\n주유소,서울,nan,127\n')
            with self.assertRaises(ValueError): convert(path,'FUEL')
    def test_fuel_official_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fuel.csv'
            path.write_text('주유소명,소재지지번주소,위도,경도\n주유소,서울,37,127\n')
            rows, skipped=convert(path,'FUEL')
            self.assertEqual(rows[0]['kind'],'FUEL')
            self.assertEqual(skipped,0)

if __name__ == '__main__': unittest.main()
