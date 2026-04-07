'use client';

export function VersionBadge({
  version,
  prerelease,
  url,
  isNew,
}: {
  version: string;
  prerelease: boolean;
  url: string;
  isNew: boolean;
}) {
  const open = (e: React.SyntheticEvent) => {
    e.preventDefault();
    e.stopPropagation();
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  const base = prerelease
    ? 'bg-amber-500/15 text-amber-500 hover:bg-amber-500/25'
    : 'bg-fd-primary/10 text-fd-primary hover:bg-fd-primary/20';

  return (
    <span
      role="link"
      tabIndex={0}
      onClick={open}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') open(e);
      }}
      className={`relative cursor-pointer rounded-md px-2 py-1 text-xs font-semibold leading-none transition-colors ${base}`}
    >
      v{version}
      {isNew && (
        <span
          aria-hidden
          className="absolute -top-0.5 -right-0.5 size-2 rounded-full bg-emerald-500 animate-[dot-pulse_2s_ease-in-out_infinite]"
        />
      )}
    </span>
  );
}
