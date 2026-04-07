import Image from 'next/image';
import { readFileSync } from 'fs';
import { join } from 'path';
import { VersionBadge } from './version-badge';

const basePath = process.env.NODE_ENV === 'production' ? '/Nimbus' : '';

const NEW_THRESHOLD_DAYS = 14;

interface ReleaseInfo {
  version: string;
  prerelease: boolean;
  url: string;
  isNew: boolean;
}

async function getLatestRelease(): Promise<ReleaseInfo> {
  try {
    const res = await fetch(
      'https://api.github.com/repos/NimbusPowered/Nimbus/releases?per_page=1',
      {
        headers: { Accept: 'application/vnd.github+json' },
        next: { revalidate: false }, // static: fetched once at build time
      },
    );
    if (res.ok) {
      const [release] = await res.json();
      if (release?.tag_name) {
        const publishedAt = release.published_at ? new Date(release.published_at) : null;
        const ageInDays = publishedAt
          ? (Date.now() - publishedAt.getTime()) / (1000 * 60 * 60 * 24)
          : Infinity;
        return {
          version: release.tag_name.replace(/^v/, ''),
          prerelease: release.prerelease === true,
          url: release.html_url,
          isNew: ageInDays < NEW_THRESHOLD_DAYS,
        };
      }
    }
  } catch {
    // GitHub API unavailable — fall through
  }

  // Fallback: read from gradle.properties
  try {
    const props = readFileSync(join(process.cwd(), '..', 'gradle.properties'), 'utf-8');
    const match = props.match(/nimbusVersion\s*=\s*(.+)/);
    if (match?.[1]) {
      return { version: match[1].trim(), prerelease: false, url: 'https://github.com/NimbusPowered/Nimbus/releases', isNew: false };
    }
  } catch {
    // ignore
  }

  return { version: 'dev', prerelease: false, url: 'https://github.com/NimbusPowered/Nimbus/releases', isNew: false };
}

export async function NavTitle() {
  const release = await getLatestRelease();

  return (
    <span className="inline-flex items-center gap-2">
      <Image src={`${basePath}/icon.png`} alt="" width={24} height={24} className="shrink-0" />
      Nimbus
      <VersionBadge version={release.version} prerelease={release.prerelease} url={release.url} isNew={release.isNew} />
    </span>
  );
}
