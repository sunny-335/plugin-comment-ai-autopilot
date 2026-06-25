export async function computeGravatarHash(email: string): Promise<string> {
  if (!email || email.trim() === '') return ''
  const normalizedEmail = email.trim().toLowerCase()
  const encoder = new TextEncoder()
  const data = encoder.encode(normalizedEmail)
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

export function getGravatarUrl(hash: string): string {
  if (!hash) return ''
  return `https://cn.cravatar.com/avatar/${hash}`
}
