import os
import unittest
import tempfile
import shutil
from pathlib import Path
from unittest.mock import patch, MagicMock
from core.system_ops import open_file_in_os, show_in_explorer
from indexing.folder_watcher import StorageFolderWatcher, StorageChangeHandler, IGNORED_PATH_PATTERNS
from core.coordinator import EngineCoordinator

class TestFileLaunchAndWatcherExclusions(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.test_file = self.temp_dir / "sample_test_doc.txt"
        self.test_file.write_text("Hello StorageSense file launcher test.")

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_open_file_non_existent(self):
        fake_path = self.temp_dir / "does_not_exist.pdf"
        self.assertFalse(open_file_in_os(str(fake_path)))

    def test_show_in_explorer_non_existent(self):
        fake_path = self.temp_dir / "does_not_exist.pdf"
        self.assertFalse(show_in_explorer(str(fake_path)))

    @patch("core.system_ops.subprocess.Popen")
    def test_open_file_success(self, mock_popen):
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc
        result = open_file_in_os(str(self.test_file))
        self.assertTrue(result)
        mock_popen.assert_called_once()
        cmd = mock_popen.call_args[0][0]
        self.assertIn("cmd.exe /c start", cmd)

    @patch("core.system_ops.subprocess.Popen")
    def test_show_in_explorer_file(self, mock_popen):
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc
        result = show_in_explorer(str(self.test_file))
        self.assertTrue(result)
        mock_popen.assert_called_once()
        cmd = mock_popen.call_args[0][0]
        self.assertIn("cmd.exe /c start explorer.exe /select,", cmd)
        self.assertIn(f'"{os.path.normpath(str(self.test_file))}"', cmd)

    @patch("core.system_ops.os.startfile")
    def test_show_in_explorer_dir(self, mock_startfile):
        mock_startfile.return_value = None
        result = show_in_explorer(str(self.temp_dir))
        self.assertTrue(result)
        mock_startfile.assert_called_once()

    def test_watcher_ignores_project_and_internal_paths(self):
        handler = StorageChangeHandler(search_engine=MagicMock())
        # Internal patterns
        self.assertTrue(handler._is_ignored(Path(r"F:\ASCENT\idea\pc_port\core\coordinator.py")))
        self.assertTrue(handler._is_ignored(Path(r"C:\workspace\project\.git\HEAD")))
        self.assertTrue(handler._is_ignored(Path(r"C:\workspace\.venv\lib\site.py")))
        self.assertTrue(handler._is_ignored(Path(r"F:\data\.trash\old_file.txt")))
        self.assertTrue(handler._is_ignored(Path(r"C:\docs\~$temp_word.docx")))

        # Valid user document
        self.assertFalse(handler._is_ignored(Path(r"C:\Users\User\Documents\Resume.pdf")))
        self.assertFalse(handler._is_ignored(Path(r"D:\College\CET415_QuestionPaper.pdf")))

    def test_watcher_refuses_to_schedule_project_dir(self):
        watcher = StorageFolderWatcher(search_engine=MagicMock())
        watcher.watch_folder(Path(r"F:\ASCENT\idea\pc_port"))
        # Should NOT add pc_port to watched paths
        self.assertEqual(len(watcher.watched_paths), 0)

if __name__ == "__main__":
    unittest.main()
