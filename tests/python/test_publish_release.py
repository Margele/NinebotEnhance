"""Check release side effects without contacting GitHub or uploading files."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import publish_release as release


class PublishReleaseTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.folder = self.root / 'dist/release'
        self.folder.mkdir(parents=True)
        (self.root / 'build').mkdir()
        self.commit = 'a' * 40
        self.names = ['NinebotEnhance-1.0.0.apk', 'NinebotEnhance-1.0.0-source.zip', 'BUILD-INFO.json']
        for name in self.names[:2]:
            (self.folder / name).write_bytes(b'test artifact')
        (self.folder / 'BUILD-INFO.json').write_text(json.dumps({
            'version': '1.0.0', 'commit': self.commit, 'kind': 'release', 'host_assertions': 751,
        }))
        (self.folder / 'SHA256SUMS.txt').write_text(''.join(
            release.sha256(self.folder / name) + '  ' + name + '\n' for name in self.names))
        self.enterContext(patch.object(release, 'ROOT', self.root))
        def resolve(*args):
            # HEAD is the checked-out release commit; "<sha>^{commit}" peels a tag to the commit it names.
            if args[0] == 'rev-parse' and args[1].endswith('^{commit}'):
                return args[1][:-len('^{commit}')]
            return self.commit
        self.enterContext(patch.object(release, 'git', side_effect=resolve))
        self.enterContext(patch.dict(os.environ, {
            'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.0.0',
            'GITHUB_REPOSITORY': 'owner/project', 'GITHUB_SHA': self.commit,
        }, clear=True))
        missing = release.subprocess.CalledProcessError(1, 'gh')
        self.query = self.enterContext(patch.object(release.subprocess, 'check_output', side_effect=missing))
        self.publish = self.enterContext(patch.object(release.subprocess, 'run'))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))

    def test_tag_push_publishes_version_and_all_files(self):
        release.main()
        command = self.publish.call_args.args[0]
        self.assertEqual(command[:4], ['gh', 'release', 'create', 'v1.0.0'])
        self.assertEqual(command[command.index('--target') + 1], self.commit)
        self.assertEqual(set(command[-4:]), {str(self.folder / name) for name in self.names + ['SHA256SUMS.txt']})
        self.assertNotIn('--prerelease', command)

    def test_manual_tag_run_publishes(self):
        os.environ['GITHUB_EVENT_NAME'] = 'workflow_dispatch'
        release.main()
        self.publish.assert_called_once()

    def test_branches_other_events_and_wrong_tags_cannot_publish(self):
        for event, ref in [('pull_request', 'refs/tags/v1.0.0'), ('push', 'refs/heads/main'), ('push', 'refs/heads/feature'),
                           ('workflow_dispatch', 'refs/heads/main'), ('push', 'refs/tags/v1.0.1'), ('push', 'refs/tags/1.0.0')]:
            with self.subTest(event=event, ref=ref), patch.dict(os.environ, {'GITHUB_EVENT_NAME': event, 'GITHUB_REF': ref}):
                with self.assertRaises(SystemExit):
                    release.main()
        self.query.assert_not_called()
        self.publish.assert_not_called()

    def test_mismatched_commit_cannot_publish(self):
        os.environ['GITHUB_SHA'] = 'b' * 40
        with self.assertRaises(SystemExit):
            release.main()
        self.query.assert_not_called()
        self.publish.assert_not_called()

    def test_changed_apk_cannot_publish(self):
        (self.folder / self.names[0]).write_bytes(b'changed')
        with self.assertRaises(SystemExit):
            release.main()
        self.query.assert_not_called()
        self.publish.assert_not_called()

    def test_existing_release_is_kept(self):
        self.query.side_effect = [json.dumps({
            'isDraft': False, 'url': 'https://github.com/owner/project/releases/tag/v1.0.0',
            'assets': [{'name': name, 'size': 1} for name in self.names + ['SHA256SUMS.txt']],
        })]
        release.main()
        self.publish.assert_not_called()

    def test_incomplete_existing_release_fails(self):
        self.query.side_effect = [json.dumps({
            'isDraft': False, 'assets': [], 'url': 'https://github.com/owner/project/releases/tag/v1.0.0',
        })]
        with self.assertRaises(SystemExit):
            release.main()
        self.publish.assert_not_called()


if __name__ == '__main__':
    unittest.main()
