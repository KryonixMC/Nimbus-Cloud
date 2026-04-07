import Image from 'next/image';

const basePath = process.env.NODE_ENV === 'production' ? '/Nimbus' : '';

export function NavTitle() {
  return (
    <span className="inline-flex items-center gap-2">
      <Image src={`${basePath}/icon.png`} alt="" width={24} height={24} className="shrink-0" />
      Nimbus
    </span>
  );
}
