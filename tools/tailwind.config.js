// Настройки Tailwind — те же, что были у CDN-версии в index.html
module.exports = {
    future: { hoverOnlyWhenSupported: true },
    theme: {
        extend: {
            fontFamily: { sans: ['Inter', 'sans-serif'] },
            colors: {
                yx: {
                    bg: '#0E0F12', card: '#191A1E', pill: '#25272D', pillHover: '#30333B', divider: '#26282E',
                    muted: '#8E929B', accent: '#4C8BF5', sheet: '#1B1C20', panel: '#2A2B30', chip: '#3A4152', chipHover: '#465066'
                }
            }
        }
    }
};
