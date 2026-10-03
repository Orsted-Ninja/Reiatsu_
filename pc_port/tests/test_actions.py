import unittest
import sys
from pathlib import Path
import tempfile

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from actions.trash_manager import TrashManager

class TestTrashManager(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(dir=str(BASE_DIR / ".cache" / "tmp")))
        self.trash_dir = self.temp_dir / ".test_trash"
        self.manager = TrashManager(trash_dir=self.trash_dir)

    def test_trash_and_restore(self):
        sample_file = self.temp_dir / "sample_doc.txt"
        sample_file.write_text("Hello StorageSense Safe Trash", encoding="utf-8")
        self.assertTrue(sample_file.exists())

        # Move to trash
        entry = self.manager.move_to_trash(sample_file)
        self.assertIsNotNone(entry)
        self.assertFalse(sample_file.exists())

        trash_path = Path(entry["trash_path"])
        self.assertTrue(trash_path.exists())

        # Restore from trash
        restored = self.manager.restore(entry["id"])
        self.assertTrue(restored)
        self.assertTrue(sample_file.exists())
        self.assertEqual(sample_file.read_text(encoding="utf-8"), "Hello StorageSense Safe Trash")

    def test_restore_collision_handling(self):
        sample_file = self.temp_dir / "collision_doc.txt"
        sample_file.write_text("Original File Content", encoding="utf-8")
        entry = self.manager.move_to_trash(sample_file)
        self.assertIsNotNone(entry)

        # Create a new conflicting file at the original path
        sample_file.write_text("New Conflicting Content", encoding="utf-8")

        # Restore from trash
        restored = self.manager.restore(entry["id"])
        self.assertTrue(restored)
        # Verify original file was preserved
        self.assertEqual(sample_file.read_text(encoding="utf-8"), "New Conflicting Content")
        # Verify restored file got non-destructive collision-safe name
        restored_file = self.temp_dir / "collision_doc (restored_1).txt"
        self.assertTrue(restored_file.exists())
        self.assertEqual(restored_file.read_text(encoding="utf-8"), "Original File Content")

if __name__ == "__main__":
    unittest.main()
