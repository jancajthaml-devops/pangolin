package client

import (
	"fmt"
	"net"

	"comp"
	"tun"
)

type PClient struct {
	ServerAdd string
	UdpConn   net.Conn
	TunConn   tun.Tun
}

func NewPClient(sadd string, tname string, mtu int) (*PClient, error) {
	conn, err := net.Dial("udp", sadd)
	if err != nil {
		return nil, err
	}
	tun, err := tun.NewLinuxTun(tname, mtu)
	if err != nil {
		return nil, err
	}

	return &PClient{
		ServerAdd: sadd,
		UdpConn:   conn,
		TunConn:   tun,
	}, nil
}

func (c *PClient) sendToServer() {
	data := make([]byte, c.TunConn.GetMtu()*2)
	for {
		if n, err := c.TunConn.Read(data); err == nil && n > 0 {
			cmpData := comp.CompressGzip(data[:n])
			c.UdpConn.Write(cmpData)
			fmt.Printf("[send] Len:%d\n Content:%s\n", n, string(data[:n]))
		}
	}
}

func (c *PClient) recvFromServer() error {
	data := make([]byte, c.TunConn.GetMtu()*2)
	for {
		if n, err := c.UdpConn.Read(data); err == nil && n > 0 {
			uncmpData, err2 := comp.UncompressGzip(data[:n])
			if err2 != nil {
				continue
			}
			c.TunConn.Write(uncmpData)
			fmt.Printf("[recv] Len:%d\n Content:%s\n", len(uncmpData), string(uncmpData))
		}
	}
}

func (c *PClient) Start() error {
	go c.sendToServer()
	go c.recvFromServer()
	return nil
}

func (c *PClient) Stop() error {
	c.UdpConn.Close()
	c.TunConn.Close()
	return nil
}
