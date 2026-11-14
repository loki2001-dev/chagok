import unittest
from build_pack import simplify, tiles

class PackTests(unittest.TestCase):
    def test_long_straight_geometry_reduced_with_endpoints(self):
        self.assertEqual(simplify([(37,127),(37.001,127),(37.002,127)]), [(37,127),(37.002,127)])
    def test_corner_preserved(self):
        p = [(37,127),(37.001,127),(37.001,127.001)]
        self.assertEqual(simplify(p),p)
    def test_all_crossed_tiles_registered_not_only_endpoints(self):
        self.assertEqual(len(list(tiles((37.001,127.001),(37.061,127.001)))),4)
    def test_closed_loop_not_collapsed(self):
        p = [(37,127),(37.001,127),(37.001,127.001),(37,127)]
        self.assertEqual(simplify(p),p)

if __name__ == '__main__':
    unittest.main()
