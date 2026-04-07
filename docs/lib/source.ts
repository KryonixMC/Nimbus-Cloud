import { docs } from '@/.source';
import { createMDXSource } from 'fumadocs-mdx';
import { loader } from 'fumadocs-core/source';

const mdxSource = createMDXSource(docs.docs, docs.meta);
// fumadocs-mdx returns files as a function, but loader expects an array
const files = (mdxSource.files as unknown as () => any[])();

export const source = loader({
  baseUrl: '/docs',
  source: { files },
});
