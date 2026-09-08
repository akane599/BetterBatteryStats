"""Keep actionable verification results visible without downloading CI ZIPs."""
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

for path in sorted(Path('app/build/test-results').rglob('TEST-*.xml')):
    root = ET.parse(path).getroot()
    print(f"{root.get('name')}: {root.get('tests')} tests, {root.get('failures')} failures, {root.get('errors')} errors")

new_errors = 0
for path in Path('app/build/reports').glob('lint-results-*.xml'):
    issues = ET.parse(path).getroot().findall('issue')
    print('Legacy + modern lint totals:', dict(Counter(issue.get('severity') for issue in issues)))
    for issue in issues:
        files = [loc.get('file', '') for loc in issue.findall('location')]
        if any('/modern/' in file or '/modern_' in file for file in files):
            print('MODERN LINT:', issue.get('severity'), issue.get('id'), issue.get('message'), files)

            if issue.get('severity') in {'Error', 'Fatal'}:
                new_errors += 1
if new_errors:
    raise SystemExit(f'{new_errors} lint errors in the new backend')
